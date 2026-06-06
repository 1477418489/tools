package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.util.TimeUtils;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private volatile boolean logProcessing = false;
    private TextArea logArea;

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
        logExecutor.scheduleWithFixedDelay(this::flushLogs,
                LOG_FLUSH_INTERVAL, LOG_FLUSH_INTERVAL, TimeUnit.MILLISECONDS);
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

        if (logQueue.size() > LOG_BATCH_SIZE * 2) {
            flushLogs();
        }
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
        logExecutor.shutdownNow();
        logQueue.clear();
        logArea = null;
    }

    private void flushLogs() {
        if (logProcessing || !isLogAreaAvailable() || logQueue.isEmpty()) {
            return;
        }

        logProcessing = true;
        try {
            StringBuilder batch = new StringBuilder();
            int count = 0;

            while (count < LOG_BATCH_SIZE && !logQueue.isEmpty()) {
                String log = logQueue.poll();
                if (log != null) {
                    batch.append(log);
                    count++;
                }
            }

            if (batch.length() > 0) {
                String logsToAppend = batch.toString();
                Platform.runLater(() -> appendToLogArea(logsToAppend));
            }
        } finally {
            logProcessing = false;
        }
    }

    private void appendToLogArea(String message) {
        if (!isLogAreaAvailable()) {
            return;
        }

        try {
            String currentText = logArea.getText();
            if (currentText.length() > 10000) {
                int cutIndex = currentText.indexOf('\n', 2000);
                if (cutIndex > 0) {
                    logArea.deleteText(0, cutIndex + 1);
                }
            }

            logArea.appendText(message);
            if (Math.random() < 0.3) {
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        } catch (Exception e) {
            // Ignore UI log append failures while the tab is being torn down.
        }
    }

    private boolean isLogAreaAvailable() {
        return logArea != null && logArea.getScene() != null;
    }

    private void trimLogQueue() {
        while (logQueue.size() > MAX_LOG_QUEUE_SIZE) {
            logQueue.poll();
        }
    }
}
