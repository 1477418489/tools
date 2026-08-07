package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.util.TimeUtils;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 域名保活服务的批量日志缓冲和 UI 刷新。
 *
 * @author wwj
 */
public class KeepAliveLogBuffer implements ModuleLogger {
    private static final int LOG_BATCH_SIZE = 10;
    private static final int MAX_LOG_QUEUE_SIZE = 200;
    private static final long LOG_FLUSH_INTERVAL = 100;

    private final Queue<String> logQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService logExecutor;
    private final AtomicBoolean logProcessing = new AtomicBoolean();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicLong logGeneration = new AtomicLong();
    private volatile TextArea logArea;

    /**
     * 创建日志缓冲器。
     *
     * @param logArea 日志输出区域
     */
    public KeepAliveLogBuffer(TextArea logArea) {
        this.logArea = logArea;
        logExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("KeepAlive-LogProcessor");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /**
     * 记录保活模块日志。
     *
     * @param level 日志级别
     * @param message 日志内容
     */
    @Override
    public void log(String level, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        String formattedMessage = String.format("[%s][%s] %s\n",
                TimeUtils.getCurrentDateTime(), level, message);

        logQueue.offer(formattedMessage);
        trimLogQueue();
        scheduleFlush(logQueue.size() > LOG_BATCH_SIZE * 2 ? 0 : LOG_FLUSH_INTERVAL);
    }

    /**
     * 获取日志输出区域。
     *
     * @return 日志输出区域
     */
    @Override
    public TextArea getLogArea() {
        return logArea;
    }

    /**
     * 记录调试日志，队列积压时自动降噪。
     *
     * @param message 日志内容
     */
    @Override
    public void debug(String message) {
        if (logQueue.size() < 5) {
            log("DEBUG", message);
        }
    }

    /**
     * 停止日志刷新并释放 UI 引用。
     */
    public void shutdown() {
        logGeneration.incrementAndGet();
        logArea = null;
        logExecutor.shutdownNow();
        logQueue.clear();
        logProcessing.set(false);
        flushScheduled.set(false);
    }

    /**
     * 清除尚未显示的日志，避免清空后旧批次再次写回界面。
     */
    public void clearPendingLogs() {
        logGeneration.incrementAndGet();
        logQueue.clear();
    }

    private void flushLogs() {
        flushScheduled.set(false);
        if (logArea == null || logQueue.isEmpty()) {
            return;
        }
        if (!logProcessing.compareAndSet(false, true)) {
            return;
        }

        StringBuilder batch = new StringBuilder();
        int count = 0;
        while (count < LOG_BATCH_SIZE) {
            String log = logQueue.poll();
            if (log == null) {
                break;
            }
            batch.append(log);
            count++;
        }

        if (batch.isEmpty()) {
            logProcessing.set(false);
            return;
        }

        String logsToAppend = batch.toString();
        long generation = logGeneration.get();
        try {
            Platform.runLater(() -> {
                try {
                    if (generation == logGeneration.get()) {
                        appendToLogArea(logsToAppend);
                    }
                } finally {
                    logProcessing.set(false);
                    if (!logQueue.isEmpty()) {
                        scheduleFlush(0);
                    }
                }
            });
        } catch (IllegalStateException e) {
            logProcessing.set(false);
        }
    }

    private void scheduleFlush(long delayMillis) {
        if (logArea == null || logExecutor.isShutdown()
                || !flushScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            logExecutor.schedule(this::flushLogs, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            flushScheduled.set(false);
        }
    }

    private void appendToLogArea(String message) {
        TextArea area = logArea;
        if (area == null || area.getScene() == null) {
            return;
        }

        try {
            String currentText = area.getText();
            if (currentText.length() > 10000) {
                int cutIndex = currentText.indexOf('\n', 2000);
                if (cutIndex > 0) {
                    area.deleteText(0, cutIndex + 1);
                }
            }

            area.appendText(message);
        } catch (Exception e) {
            // Ignore UI log append failures while the tab is being torn down.
        }
    }

    private void trimLogQueue() {
        while (logQueue.size() > MAX_LOG_QUEUE_SIZE) {
            logQueue.poll();
        }
    }
}
