package plugin.javafxtools.service;

import plugin.javafxtools.model.LogMonitorConfig;
import plugin.javafxtools.model.LogMonitorMatch;
import plugin.javafxtools.model.LogMonitorRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Polls appended log lines and reports matching monitor rules. */
public final class LogMonitorService implements AutoCloseable {
    private static final long DEFAULT_POLL_MILLIS = 500L;
    static final int MAX_READ_BYTES_PER_POLL = 256 * 1024;
    static final int MAX_LINES_PER_POLL = 200;
    static final int MAX_MATCHES_PER_POLL = 200;

    public interface Listener {
        void onStatusChanged(LogMonitorStatus status, String detail);
        void onMatches(List<LogMonitorMatch> matches);
        void onReadError(String message);
    }

    interface Observer {
        Observer NONE = new Observer() { };
        default void onWorkerCreated(Thread thread) { }
        default void beforeCallback(long generation, CallbackKind kind) { }
        default void onPollCompleted(long generation) { }
    }

    enum CallbackKind { ACTIVE, STOPPED }

    @FunctionalInterface
    interface LogReader {
        List<String> readAvailable(boolean flushPartial, int maxBytes, int maxLines)
                throws IOException;
    }

    @FunctionalInterface
    interface LogReaderFactory {
        LogReader create(Path path);
    }

    private final ScheduledExecutorService executor;
    private final long pollMillis;
    private final Clock clock;
    private final Observer observer;
    private final LogReaderFactory readerFactory;
    private long generation;
    private Session session;
    private ScheduledFuture<?> pollTask;
    private boolean closed;

    public LogMonitorService() {
        this(DEFAULT_POLL_MILLIS, Clock.systemUTC(), Observer.NONE);
    }

    LogMonitorService(long pollMillis, Clock clock) {
        this(pollMillis, clock, Observer.NONE);
    }

    LogMonitorService(long pollMillis, Clock clock, Observer observer) {
        this(pollMillis, clock, observer, path -> {
            LogFileTailer tailer = LogFileTailer.followNewContent(path);
            return tailer::readAvailable;
        });
    }

    LogMonitorService(long pollMillis, Clock clock, Observer observer,
                      LogReaderFactory readerFactory) {
        if (pollMillis <= 0) {
            throw new IllegalArgumentException("pollMillis must be positive");
        }
        this.pollMillis = pollMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "log-monitor-poller");
            thread.setDaemon(true);
            this.observer.onWorkerCreated(thread);
            return thread;
        };
        executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public synchronized void start(LogMonitorConfig config, Listener listener) {
        if (closed) {
            throw new IllegalStateException("Log monitor service is closed");
        }
        if (session != null) {
            return;
        }
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(listener, "listener");
        if (config.logFile() == null || config.logFile().isBlank()) {
            throw new IllegalArgumentException("Log file path must not be blank");
        }
        List<LogMonitorRule> rules = List.copyOf(Objects.requireNonNull(config.rules(), "rules"));
        LogMonitorMatcher matcher = new LogMonitorMatcher(rules);
        Path path = Path.of(config.logFile());
        Session next = new Session(++generation, listener, path, matcher, readerFactory.create(path));
        session = next;
        pollTask = executor.scheduleWithFixedDelay(() -> poll(next), 0L, pollMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (session == null) {
            return;
        }
        Listener listener = session.listener;
        session = null;
        if (pollTask != null) {
            pollTask.cancel(true);
            pollTask = null;
        }
        long stoppedGeneration = ++generation;
        executor.execute(() -> dispatchStopped(listener, stoppedGeneration));
    }

    public synchronized boolean isRunning() {
        return session != null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ++generation;
        session = null;
        if (pollTask != null) {
            pollTask.cancel(true);
            pollTask = null;
        }
        executor.shutdownNow();
    }

    private void poll(Session current) {
        try {
            if (!isCurrent(current)) {
                return;
            }
            if (current.hasPendingWork()) {
                publishPendingMatches(current);
                return;
            }
            Path path = current.path;
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class);
            } catch (NoSuchFileException exception) {
                current.lastError = null;
                publishStatus(current, LogMonitorStatus.WAITING_FOR_FILE, path.toString());
                return;
            }
            if (!attributes.isRegularFile()) {
                String message = "Log path is not a regular file: " + path;
                publishStatus(current, LogMonitorStatus.ERROR, message);
                publishReadError(current, message);
                return;
            }
            current.pendingLines.addAll(current.reader.readAvailable(
                    false, MAX_READ_BYTES_PER_POLL, MAX_LINES_PER_POLL));
            current.lastError = null;
            publishStatus(current, LogMonitorStatus.RUNNING, path.toString());
            publishPendingMatches(current);
        } catch (IOException | RuntimeException exception) {
            String detail = exception.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = exception.getClass().getSimpleName();
            }
            String message = "Unable to read log file " + current.path + ": " + detail;
            publishStatus(current, LogMonitorStatus.ERROR, message);
            publishReadError(current, message);
        } finally {
            observer.onPollCompleted(current.generation);
        }
    }

    private void publishPendingMatches(Session current) {
        List<LogMonitorMatch> matches = collectMatches(current);
        if (!matches.isEmpty()) {
            publishMatches(current, List.copyOf(matches));
        }
    }

    private List<LogMonitorMatch> collectMatches(Session current) {
        List<LogMonitorMatch> matches = new ArrayList<>(MAX_MATCHES_PER_POLL);
        while (matches.size() < MAX_MATCHES_PER_POLL) {
            if (current.nextRuleIndex >= current.matchingRules.size()) {
                String nextLine = current.pendingLines.pollFirst();
                if (nextLine == null) {
                    break;
                }
                current.currentLine = nextLine;
                current.currentMatchedAt = clock.instant();
                current.matchingRules = current.matcher.matchingRules(nextLine);
                current.nextRuleIndex = 0;
                if (current.matchingRules.isEmpty()) {
                    current.clearCurrentLine();
                    continue;
                }
            }

            LogMonitorRule rule = current.matchingRules.get(current.nextRuleIndex++);
            matches.add(new LogMonitorMatch(rule.id(), rule.name(), rule.expression(),
                    current.path, current.currentLine, current.currentMatchedAt));
            if (current.nextRuleIndex >= current.matchingRules.size()) {
                current.clearCurrentLine();
            }
        }
        return matches;
    }

    private synchronized boolean isCurrent(Session candidate) {
        return !closed && session == candidate && generation == candidate.generation;
    }

    private void publishStatus(Session current, LogMonitorStatus status, String detail) {
        if (status == current.status && Objects.equals(detail, current.detail)) {
            return;
        }
        current.status = status;
        current.detail = detail;
        dispatchActive(current, () -> current.listener.onStatusChanged(status, detail));
    }

    private void publishReadError(Session current, String message) {
        if (Objects.equals(message, current.lastError)) {
            return;
        }
        current.lastError = message;
        dispatchActive(current, () -> current.listener.onReadError(message));
    }

    private void publishMatches(Session current, List<LogMonitorMatch> matches) {
        dispatchActive(current, () -> current.listener.onMatches(matches));
    }

    private void dispatchActive(Session current, Runnable callback) {
        observer.beforeCallback(current.generation, CallbackKind.ACTIVE);
        synchronized (this) {
            if (isCurrent(current)) {
                runListenerCallback(callback);
            }
        }
    }

    private void dispatchStopped(Listener listener, long stoppedGeneration) {
        observer.beforeCallback(stoppedGeneration, CallbackKind.STOPPED);
        synchronized (this) {
            if (!closed && session == null && generation == stoppedGeneration) {
                runListenerCallback(() -> listener.onStatusChanged(LogMonitorStatus.STOPPED, ""));
            }
        }
    }

    private static void runListenerCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // A UI listener must not terminate the persistent polling task.
        }
    }

    private static final class Session {
        private final long generation;
        private final Listener listener;
        private final Path path;
        private final LogMonitorMatcher matcher;
        private final LogReader reader;
        private final ArrayDeque<String> pendingLines = new ArrayDeque<>();
        private List<LogMonitorRule> matchingRules = List.of();
        private int nextRuleIndex;
        private String currentLine;
        private java.time.Instant currentMatchedAt;
        private LogMonitorStatus status;
        private String detail;
        private String lastError;

        private Session(long generation, Listener listener, Path path, LogMonitorMatcher matcher,
                        LogReader reader) {
            this.generation = generation;
            this.listener = listener;
            this.path = path;
            this.matcher = matcher;
            this.reader = reader;
        }

        private boolean hasPendingWork() {
            return !pendingLines.isEmpty() || nextRuleIndex < matchingRules.size();
        }

        private void clearCurrentLine() {
            matchingRules = List.of();
            nextRuleIndex = 0;
            currentLine = null;
            currentMatchedAt = null;
        }
    }
}
