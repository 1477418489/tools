package plugin.javafxtools.service;

import plugin.javafxtools.model.HttpRequestResult;
import plugin.javafxtools.model.LogMonitorAutomation;
import plugin.javafxtools.model.LogMonitorMatch;
import plugin.javafxtools.model.LogRemoteMatchAction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Applies count and remote-response gates before dispatching automated input. */
public final class LogMonitorAutomationService implements AutoCloseable {
    private static final int HTTP_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_PENDING_ACTIONS = 32;

    @FunctionalInterface
    interface RemoteResponseReader {
        Response read(String url) throws IOException;
    }

    @FunctionalInterface
    interface InputSender {
        WindowsInputSender.Result send(String targetWindow, String text, boolean pressEnter);
    }

    public enum EventType {
        EXECUTED,
        SKIPPED,
        ERROR
    }

    public record Event(EventType type, long matchCount, String message) {
        public Event {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(message, "message");
        }
    }

    public interface Listener {
        void onEvent(Event event);
    }

    record Response(int statusCode, String body) {
        Response {
            Objects.requireNonNull(body, "body");
        }
    }

    private final RemoteResponseReader responseReader;
    private final InputSender inputSender;
    private final ThreadPoolExecutor executor;
    private LogMonitorAutomation automation;
    private Listener listener;
    private long generation;
    private long triggerMatchCount;
    private long successfulExecutions;
    private long reservedExecutions;
    private int pendingActions;
    private boolean queueLimitReported;
    private boolean closed;

    public LogMonitorAutomationService() {
        this(defaultResponseReader(), new WindowsInputSender()::send);
    }

    LogMonitorAutomationService(RemoteResponseReader responseReader, InputSender inputSender) {
        this.responseReader = Objects.requireNonNull(responseReader, "responseReader");
        this.inputSender = Objects.requireNonNull(inputSender, "inputSender");
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_ACTIONS), runnable -> {
                    Thread thread = new Thread(runnable, "log-monitor-automation");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public synchronized void configure(LogMonitorAutomation automation, Listener listener) {
        if (closed) {
            throw new IllegalStateException("Log monitor automation service is closed");
        }
        this.automation = Objects.requireNonNull(automation, "automation");
        this.listener = Objects.requireNonNull(listener, "listener");
        generation++;
        executor.getQueue().clear();
        triggerMatchCount = 0;
        successfulExecutions = 0;
        reservedExecutions = 0;
        pendingActions = 0;
        queueLimitReported = false;
    }

    public void acceptAll(List<LogMonitorMatch> matches) {
        Objects.requireNonNull(matches, "matches");
        List<ScheduledAction> actions = new ArrayList<>();
        Event overflowEvent = null;
        long overflowGeneration = -1;
        synchronized (this) {
            if (closed || automation == null || !automation.enabled()) {
                return;
            }
            for (LogMonitorMatch match : matches) {
                if (match == null || !automation.triggerRuleIds().contains(match.ruleId())) {
                    continue;
                }
                triggerMatchCount = saturatedIncrement(triggerMatchCount);
                if (!isEligible(triggerMatchCount, automation)
                        || hasReachedLimit(successfulExecutions, reservedExecutions,
                        automation.maxExecutions())) {
                    continue;
                }
                if (pendingActions >= MAX_PENDING_ACTIONS) {
                    if (!queueLimitReported) {
                        queueLimitReported = true;
                        overflowEvent = new Event(EventType.ERROR, triggerMatchCount,
                                "自动响应队列已满，后续符合条件的动作已跳过");
                        overflowGeneration = generation;
                    }
                    continue;
                }
                reservedExecutions = saturatedIncrement(reservedExecutions);
                pendingActions++;
                actions.add(new ScheduledAction(generation, triggerMatchCount, automation));
            }
        }
        if (overflowEvent != null) {
            publishIfCurrent(overflowGeneration, overflowEvent);
        }
        for (ScheduledAction action : actions) {
            submit(action);
        }
    }

    public synchronized void reset() {
        generation++;
        executor.getQueue().clear();
        automation = null;
        listener = null;
        triggerMatchCount = 0;
        successfulExecutions = 0;
        reservedExecutions = 0;
        pendingActions = 0;
        queueLimitReported = false;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        generation++;
        automation = null;
        listener = null;
        executor.shutdownNow();
    }

    private void submit(ScheduledAction action) {
        try {
            executor.execute(() -> execute(action));
        } catch (RejectedExecutionException exception) {
            actionFinished(action.generation(), false);
            if (isCurrent(action.generation())) {
                publishIfCurrent(action.generation(), new Event(EventType.ERROR, action.matchCount(),
                        "自动响应任务无法排队"));
            }
        }
    }

    private void execute(ScheduledAction action) {
        boolean executed = false;
        try {
            if (!isCurrent(action.generation())) {
                return;
            }
            LogMonitorAutomation config = action.automation();
            if (config.remoteCheckEnabled() && !remoteAllows(action, config)) {
                return;
            }
            if (!isCurrent(action.generation())) {
                return;
            }
            WindowsInputSender.Result result = inputSender.send(
                    config.targetWindow(), config.typeText() ? config.text() : "",
                    config.pressEnter());
            if (!isCurrent(action.generation())) {
                return;
            }
            executed = result.success();
            publishIfCurrent(action.generation(), new Event(
                    result.success() ? EventType.EXECUTED : EventType.ERROR,
                    action.matchCount(), result.message()));
        } catch (RuntimeException exception) {
            if (isCurrent(action.generation())) {
                String detail = exception.getMessage();
                publishIfCurrent(action.generation(), new Event(EventType.ERROR, action.matchCount(),
                        "自动响应失败: " + (detail == null ? exception.getClass().getSimpleName() : detail)));
            }
        } finally {
            actionFinished(action.generation(), executed);
        }
    }

    private boolean remoteAllows(ScheduledAction action, LogMonitorAutomation config) {
        Response response;
        try {
            response = responseReader.read(config.remoteUrl());
        } catch (IOException | RuntimeException exception) {
            if (isCurrent(action.generation())) {
                publishIfCurrent(action.generation(), new Event(EventType.ERROR, action.matchCount(),
                        "远程判断请求失败，已跳过输入: " + safeMessage(exception)));
            }
            return false;
        }
        if (!isCurrent(action.generation())) {
            return false;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            publishIfCurrent(action.generation(), new Event(EventType.ERROR, action.matchCount(),
                    "远程判断返回 HTTP " + response.statusCode() + "，已跳过输入"));
            return false;
        }
        boolean contains = response.body().contains(config.remoteKeyword());
        boolean allow = contains
                ? config.remoteMatchAction() == LogRemoteMatchAction.CONTINUE_INPUT
                : config.remoteMatchAction() == LogRemoteMatchAction.NO_ACTION;
        if (!allow) {
            publishIfCurrent(action.generation(), new Event(EventType.SKIPPED, action.matchCount(),
                    contains ? "远程响应命中关键字，按配置不执行输入"
                            : "远程响应未命中关键字，按配置不执行输入"));
        }
        return allow;
    }

    private synchronized boolean isCurrent(long candidateGeneration) {
        return !closed && automation != null && automation.enabled()
                && generation == candidateGeneration;
    }

    private synchronized void actionFinished(long candidateGeneration, boolean executed) {
        if (candidateGeneration != generation) {
            return;
        }
        if (pendingActions > 0) {
            pendingActions--;
        }
        if (reservedExecutions > 0) {
            reservedExecutions--;
        }
        if (executed) {
            successfulExecutions = saturatedIncrement(successfulExecutions);
        }
    }

    private void publishIfCurrent(long candidateGeneration, Event event) {
        Listener currentListener;
        synchronized (this) {
            if (closed || automation == null || !automation.enabled()
                    || generation != candidateGeneration) {
                return;
            }
            currentListener = listener;
        }
        if (currentListener != null) {
            try {
                currentListener.onEvent(event);
            } catch (RuntimeException ignored) {
                // A UI listener must not terminate the automation worker.
            }
        }
    }

    private static boolean isEligible(long matchCount, LogMonitorAutomation automation) {
        return matchCount >= automation.startAtMatch()
                && (matchCount - automation.startAtMatch()) % automation.everyMatches() == 0;
    }

    private static boolean hasReachedLimit(long successfulExecutions,
                                           long reservedExecutions,
                                           int maxExecutions) {
        return maxExecutions > 0
                && successfulExecutions + reservedExecutions >= maxExecutions;
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private static RemoteResponseReader defaultResponseReader() {
        HttpRequestService httpService = new HttpRequestService();
        return url -> {
            HttpRequestResult response = httpService.sendRequest(url, "GET", "", "",
                    HTTP_TIMEOUT_MILLIS, HTTP_TIMEOUT_MILLIS);
            return new Response(response.statusCode(), response.rawBody());
        };
    }

    private record ScheduledAction(long generation, long matchCount,
                                   LogMonitorAutomation automation) {
    }
}
