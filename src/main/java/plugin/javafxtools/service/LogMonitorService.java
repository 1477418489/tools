package plugin.javafxtools.service;

import plugin.javafxtools.model.LogMonitorConfig;
import plugin.javafxtools.model.LogMonitorMatch;
import plugin.javafxtools.model.LogMonitorRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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

    public interface Listener {
        void onStatusChanged(LogMonitorStatus status, String detail);
        void onMatches(List<LogMonitorMatch> matches);
        void onReadError(String message);
    }

    private final ScheduledExecutorService executor;
    private final long pollMillis;
    private final Clock clock;
    private long generation;
    private Session session;
    private ScheduledFuture<?> pollTask;
    private boolean closed;

    public LogMonitorService() {
        this(DEFAULT_POLL_MILLIS, Clock.systemUTC());
    }

    LogMonitorService(long pollMillis, Clock clock) {
        if (pollMillis <= 0) {
            throw new IllegalArgumentException("pollMillis must be positive");
        }
        this.pollMillis = pollMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "log-monitor-poller");
            thread.setDaemon(true);
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
        Session next = new Session(++generation, listener, path, matcher, LogFileTailer.followNewContent(path));
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
        if (!closed && generation == stoppedGeneration) {
            listener.onStatusChanged(LogMonitorStatus.STOPPED, "");
        }
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
        if (!isCurrent(current)) {
            return;
        }
        Path path = current.path;
        if (!Files.exists(path)) {
            current.lastError = null;
            publishStatus(current, LogMonitorStatus.WAITING_FOR_FILE, path.toString());
            return;
        }
        if (!Files.isRegularFile(path)) {
            String message = "Log path is not a regular file: " + path;
            publishStatus(current, LogMonitorStatus.ERROR, message);
            publishReadError(current, message);
            return;
        }
        try {
            List<String> lines = current.tailer.readAvailable(false);
            current.lastError = null;
            publishStatus(current, LogMonitorStatus.RUNNING, path.toString());
            List<LogMonitorMatch> matches = new ArrayList<>();
            for (String line : lines) {
                for (LogMonitorRule rule : current.matcher.matchingRules(line)) {
                    matches.add(new LogMonitorMatch(rule.id(), rule.name(), rule.expression(), path, line, clock.instant()));
                }
            }
            if (!matches.isEmpty()) {
                publishMatches(current, List.copyOf(matches));
            }
        } catch (IOException exception) {
            String message = "Unable to read log file " + path + ": " + exception.getMessage();
            publishStatus(current, LogMonitorStatus.ERROR, message);
            publishReadError(current, message);
        }
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
        synchronized (this) {
            if (isCurrent(current)) {
                current.listener.onStatusChanged(status, detail);
            }
        }
    }

    private void publishReadError(Session current, String message) {
        if (Objects.equals(message, current.lastError)) {
            return;
        }
        current.lastError = message;
        synchronized (this) {
            if (isCurrent(current)) {
                current.listener.onReadError(message);
            }
        }
    }

    private void publishMatches(Session current, List<LogMonitorMatch> matches) {
        synchronized (this) {
            if (isCurrent(current)) {
                current.listener.onMatches(matches);
            }
        }
    }

    private static final class Session {
        private final long generation;
        private final Listener listener;
        private final Path path;
        private final LogMonitorMatcher matcher;
        private final LogFileTailer tailer;
        private LogMonitorStatus status;
        private String detail;
        private String lastError;

        private Session(long generation, Listener listener, Path path, LogMonitorMatcher matcher, LogFileTailer tailer) {
            this.generation = generation;
            this.listener = listener;
            this.path = path;
            this.matcher = matcher;
            this.tailer = tailer;
        }
    }
}
