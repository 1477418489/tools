package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.model.HttpRequestResult;
import plugin.javafxtools.model.HttpScheduleConfig;
import plugin.javafxtools.util.HttpUrlSupport;
import plugin.javafxtools.util.TimeUtils;

import java.io.IOException;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * HTTP 定时请求调度编排。
 *
 * @author wwj
 */
public class HttpSchedulerService {
    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private final HttpRequestService requestService;
    private final Consumer<String> infoLogger;
    private final Consumer<String> debugLogger;
    private final Consumer<String> errorLogger;
    private final Consumer<HttpRequestResult> responseConsumer;
    private final Runnable runningStateSetter;
    private final Runnable stoppedStateSetter;
    private final Consumer<Runnable> uiDispatcher;

    private ScheduledExecutorService scheduler;
    private Future<?> currentTaskFuture;
    private boolean running;
    private long runVersion;

    /**
     * 创建 HTTP 定时调度服务。
     *
     * @param requestService HTTP 请求服务
     * @param infoLogger 信息日志回调
     * @param debugLogger 调试日志回调
     * @param errorLogger 错误日志回调
     * @param responseConsumer 最新响应回调
     * @param runningStateSetter 进入运行状态的 UI 回调
     * @param stoppedStateSetter 进入停止状态的 UI 回调
     */
    public HttpSchedulerService(HttpRequestService requestService,
                                Consumer<String> infoLogger,
                                Consumer<String> debugLogger,
                                Consumer<String> errorLogger,
                                Consumer<HttpRequestResult> responseConsumer,
                                Runnable runningStateSetter,
                                Runnable stoppedStateSetter) {
        this(requestService, infoLogger, debugLogger, errorLogger,
                responseConsumer, runningStateSetter, stoppedStateSetter,
                action -> {
                    if (Platform.isFxApplicationThread()) {
                        action.run();
                    } else {
                        Platform.runLater(action);
                    }
                });
    }

    HttpSchedulerService(HttpRequestService requestService,
                         Consumer<String> infoLogger,
                         Consumer<String> debugLogger,
                         Consumer<String> errorLogger,
                         Consumer<HttpRequestResult> responseConsumer,
                         Runnable runningStateSetter,
                         Runnable stoppedStateSetter,
                         Consumer<Runnable> uiDispatcher) {
        this.requestService = requestService;
        this.infoLogger = infoLogger;
        this.debugLogger = debugLogger;
        this.errorLogger = errorLogger;
        this.responseConsumer = responseConsumer;
        this.runningStateSetter = runningStateSetter;
        this.stoppedStateSetter = stoppedStateSetter;
        this.uiDispatcher = uiDispatcher;
    }

    /**
     * 启动定时请求。
     *
     * @param config 启动配置
     */
    public synchronized void start(HttpScheduleConfig config) {
        if (running) {
            infoLogger.accept("调度器已在运行中");
            return;
        }

        if (config == null || isBlank(config.startTimeText()) || isBlank(config.intervalText())
                || isBlank(config.url()) || isBlank(config.method())) {
            errorLogger.accept("请填写所有必填字段（开始时间、间隔、URL、请求方法）");
            return;
        }
        RequestSettings requestSettings = validateRequest(config);
        if (requestSettings == null) {
            return;
        }

        try {
            Date startTime = TimeUtils.parseDateTime(config.startTimeText(), TimeUtils.DEFAULT_DATETIME_FORMAT);
            if (startTime == null) {
                errorLogger.accept("开始时间格式不正确，请使用 yyyy-MM-dd HH:mm:ss 格式");
                return;
            }

            long intervalSeconds = Long.parseLong(config.intervalText());
            if (intervalSeconds <= 0) {
                errorLogger.accept("间隔必须大于0秒");
                return;
            }
            long interval;
            try {
                interval = Math.multiplyExact(intervalSeconds, 1000L);
            } catch (ArithmeticException e) {
                errorLogger.accept("间隔数值过大");
                return;
            }

            long delay = startTime.getTime() - System.currentTimeMillis();
            if (delay < 0) {
                debugLogger.accept("开始时间已过，将立即执行第一次请求");
                delay = 0;
            }

            long currentRunVersion = beginExecution("HttpRequest-Scheduler");

            Runnable task = () -> runRequestTask(
                    currentRunVersion, config, requestSettings, false);
            currentTaskFuture = scheduler.scheduleWithFixedDelay(task, delay, interval, TimeUnit.MILLISECONDS);
            infoLogger.accept(String.format("调度器已启动，将在 %s 开始执行，间隔 %d 秒",
                    TimeUtils.formatDateTime(startTime, TimeUtils.DEFAULT_DATETIME_FORMAT), interval / 1000));
        } catch (Exception e) {
            errorLogger.accept("启动调度器失败: " + e.getMessage());
            stop();
        }
    }

    /**
     * 立即执行一次请求，不创建重复调度。
     *
     * @param config 请求配置；开始时间和间隔可留空
     */
    public synchronized void executeOnce(HttpScheduleConfig config) {
        if (running) {
            infoLogger.accept("已有请求任务正在运行");
            return;
        }

        RequestSettings requestSettings = validateRequest(config);
        if (requestSettings == null) {
            return;
        }

        try {
            long currentRunVersion = beginExecution("HttpRequest-OneShot");
            currentTaskFuture = scheduler.submit(() -> runRequestTask(
                    currentRunVersion, config, requestSettings, true));
            infoLogger.accept("已提交单次请求");
        } catch (RuntimeException e) {
            errorLogger.accept("发送单次请求失败: " + e.getMessage());
            stop();
        }
    }

    /**
     * 停止定时请求。
     */
    public void stop() {
        Future<?> taskToCancel;
        ScheduledExecutorService schedulerToStop;
        synchronized (this) {
            if (!running && currentTaskFuture == null && scheduler == null) {
                return;
            }
            running = false;
            runVersion++;
            taskToCancel = currentTaskFuture;
            schedulerToStop = scheduler;
            currentTaskFuture = null;
            scheduler = null;
        }

        if (taskToCancel != null) {
            taskToCancel.cancel(true);
        }
        if (schedulerToStop != null) {
            schedulerToStop.shutdownNow();
        }
        uiDispatcher.accept(stoppedStateSetter);
    }

    private void runRequestTask(long expectedRunVersion,
                                HttpScheduleConfig config,
                                RequestSettings requestSettings,
                                boolean stopAfterCompletion) {
        if (!publishIfCurrent(expectedRunVersion,
                () -> infoLogger.accept("准备发送 " + config.method() + " 请求到: " + config.url()))) {
            return;
        }

        try {
            HttpRequestResult result = requestService.sendRequest(
                    config.url(),
                    config.method(),
                    config.params(),
                    config.headers(),
                    requestSettings.connectTimeout(),
                    requestSettings.readTimeout()
            );
            publishOnUiIfCurrent(expectedRunVersion, () -> {
                try {
                    infoLogger.accept("请求完成: HTTP " + result.statusCode()
                            + "，耗时 " + result.elapsedMillis() + " ms");
                    responseConsumer.accept(result);
                } finally {
                    if (stopAfterCompletion) {
                        finishOneShot(expectedRunVersion);
                    }
                }
            });
        } catch (IOException e) {
            publishFailure(expectedRunVersion, "请求失败: " + e.getMessage(), stopAfterCompletion);
        } catch (Exception e) {
            publishFailure(expectedRunVersion,
                    "意外错误: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    stopAfterCompletion);
        }
    }

    private synchronized long beginExecution(String threadName) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, threadName);
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        long currentRunVersion = ++runVersion;
        uiDispatcher.accept(runningStateSetter);
        return currentRunVersion;
    }

    private RequestSettings validateRequest(HttpScheduleConfig config) {
        if (config == null || isBlank(config.url()) || isBlank(config.method())) {
            errorLogger.accept("请填写 URL 和请求方法");
            return null;
        }
        if (!HttpUrlSupport.isValid(config.url())) {
            errorLogger.accept("请输入有效的 HTTP 或 HTTPS 请求地址");
            return null;
        }
        if (!SUPPORTED_METHODS.contains(config.method())) {
            errorLogger.accept("不支持的 HTTP 请求方法: " + config.method());
            return null;
        }
        Integer connectTimeout = parsePositiveIntOrDefault(
                config.connectTimeoutText(), 5000, "连接超时");
        Integer readTimeout = parsePositiveIntOrDefault(
                config.readTimeoutText(), 10000, "读取超时");
        return connectTimeout == null || readTimeout == null
                ? null : new RequestSettings(connectTimeout, readTimeout);
    }

    private void publishFailure(long expectedRunVersion,
                                String message,
                                boolean stopAfterCompletion) {
        if (!stopAfterCompletion) {
            publishIfCurrent(expectedRunVersion, () -> errorLogger.accept(message));
            return;
        }
        publishOnUiIfCurrent(expectedRunVersion, () -> {
            try {
                errorLogger.accept(message);
            } finally {
                finishOneShot(expectedRunVersion);
            }
        });
    }

    private void finishOneShot(long expectedRunVersion) {
        ScheduledExecutorService schedulerToStop;
        synchronized (this) {
            if (!running || runVersion != expectedRunVersion) {
                return;
            }
            running = false;
            runVersion++;
            currentTaskFuture = null;
            schedulerToStop = scheduler;
            scheduler = null;
        }
        if (schedulerToStop != null) {
            schedulerToStop.shutdown();
        }
        stoppedStateSetter.run();
    }

    private synchronized boolean publishIfCurrent(long expectedRunVersion, Runnable action) {
        if (!running || runVersion != expectedRunVersion) {
            return false;
        }
        action.run();
        return true;
    }

    private boolean publishOnUiIfCurrent(long expectedRunVersion, Runnable action) {
        synchronized (this) {
            if (!running || runVersion != expectedRunVersion) {
                return false;
            }
        }
        uiDispatcher.accept(() -> publishIfCurrent(expectedRunVersion, action));
        return true;
    }

    private Integer parsePositiveIntOrDefault(String value, int defaultValue, String fieldName) {
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (Exception e) {
            // 统一在下方输出用户可读的校验反馈。
        }
        errorLogger.accept(fieldName + "必须是大于 0 的整数");
        return null;
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private record RequestSettings(int connectTimeout, int readTimeout) {
    }
}
