package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.model.HttpRequestResult;
import plugin.javafxtools.model.HttpScheduleConfig;
import plugin.javafxtools.util.TimeUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
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
    private final HttpRequestService requestService;
    private final HttpResponseFormatter responseFormatter;
    private final Consumer<String> infoLogger;
    private final Consumer<String> debugLogger;
    private final Consumer<String> errorLogger;
    private final Consumer<String> rawResponseConsumer;
    private final Runnable runningStateSetter;
    private final Runnable stoppedStateSetter;

    private ScheduledExecutorService scheduler;
    private Future<?> currentTaskFuture;
    private boolean running = false;

    /**
     * 创建 HTTP 定时调度服务。
     *
     * @param requestService HTTP 请求服务
     * @param responseFormatter 响应格式化服务
     * @param infoLogger 信息日志回调
     * @param debugLogger 调试日志回调
     * @param errorLogger 错误日志回调
     * @param rawResponseConsumer 最新响应体回调
     * @param runningStateSetter 进入运行状态的 UI 回调
     * @param stoppedStateSetter 进入停止状态的 UI 回调
     */
    public HttpSchedulerService(HttpRequestService requestService,
                                HttpResponseFormatter responseFormatter,
                                Consumer<String> infoLogger,
                                Consumer<String> debugLogger,
                                Consumer<String> errorLogger,
                                Consumer<String> rawResponseConsumer,
                                Runnable runningStateSetter,
                                Runnable stoppedStateSetter) {
        this.requestService = requestService;
        this.responseFormatter = responseFormatter;
        this.infoLogger = infoLogger;
        this.debugLogger = debugLogger;
        this.errorLogger = errorLogger;
        this.rawResponseConsumer = rawResponseConsumer;
        this.runningStateSetter = runningStateSetter;
        this.stoppedStateSetter = stoppedStateSetter;
    }

    /**
     * 启动定时请求。
     *
     * @param config 启动配置
     */
    public void start(HttpScheduleConfig config) {
        if (running) {
            infoLogger.accept("调度器已在运行中");
            return;
        }

        if (isBlank(config.startTimeText()) || isBlank(config.intervalText()) || isBlank(config.url())) {
            errorLogger.accept("请填写所有必填字段（开始时间、间隔、URL）");
            return;
        }

        try {
            Date startTime = TimeUtils.parseDateTime(config.startTimeText(), TimeUtils.DEFAULT_DATETIME_FORMAT);
            if (startTime == null) {
                errorLogger.accept("开始时间格式不正确，请使用 yyyy-MM-dd HH:mm:ss 格式");
                return;
            }

            long interval = Long.parseLong(config.intervalText()) * 1000L;
            if (interval <= 0) {
                errorLogger.accept("间隔必须大于0秒");
                return;
            }

            long delay = startTime.getTime() - System.currentTimeMillis();
            if (delay < 0) {
                debugLogger.accept("开始时间已过，将立即执行第一次请求");
                delay = 0;
            }

            int connectTimeout = parseIntOrDefault(config.connectTimeoutText(), 5000);
            int readTimeout = parseIntOrDefault(config.readTimeoutText(), 10000);
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HttpRequest-Scheduler");
                t.setDaemon(true);
                return t;
            });
            running = true;
            Platform.runLater(runningStateSetter);

            Runnable task = () -> runRequestTask(config, connectTimeout, readTimeout);
            currentTaskFuture = scheduler.scheduleWithFixedDelay(task, delay, interval, TimeUnit.MILLISECONDS);
            infoLogger.accept(String.format("调度器已启动，将在 %s 开始执行，间隔 %d 秒",
                    TimeUtils.formatDateTime(startTime, TimeUtils.DEFAULT_DATETIME_FORMAT), interval / 1000));
        } catch (Exception e) {
            errorLogger.accept("启动调度器失败: " + e.getMessage());
            stop();
        }
    }

    /**
     * 停止定时请求。
     */
    public void stop() {
        running = false;
        if (currentTaskFuture != null) {
            currentTaskFuture.cancel(true);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        Platform.runLater(stoppedStateSetter);
    }

    private void runRequestTask(HttpScheduleConfig config, int connectTimeout, int readTimeout) {
        try {
            infoLogger.accept("准备发送 " + config.method() + " 请求到: " + config.url());
            if (Arrays.asList("POST", "PUT", "PATCH").contains(config.method())) {
                infoLogger.accept("请求体: " + config.params());
            }

            HttpRequestResult result = requestService.sendRequest(
                    config.url(),
                    config.method(),
                    config.params(),
                    config.headers(),
                    connectTimeout,
                    readTimeout
            );
            rawResponseConsumer.accept(result.rawBody());
            String displayResponse = responseFormatter.formatForPreview(result.rawBody(), config.responseFormat());
            infoLogger.accept("请求完成：\n" + result.logContent()
                    + (displayResponse != null ? "\n[响应体美化预览]\n" + displayResponse : ""));
        } catch (IOException e) {
            errorLogger.accept("请求失败: " + e.getMessage());
        } catch (Exception e) {
            errorLogger.accept("意外错误: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
