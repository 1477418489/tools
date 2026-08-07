package plugin.javafxtools.base;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.LogTextTrimmer;
import plugin.javafxtools.util.TimeUtils;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 控制器基类 - 封装通用的日志、清空日志、清理资源逻辑
 */
public abstract class BaseController implements ModuleLogger {
    /**
     * 默认模块日志最大保留行数。
     */
    private static final int MAX_LOG_LINES = 800;
    private static final int MAX_LOG_CHARACTERS = 1_000_000;
    private static final int LOG_TRIM_TARGET_CHARACTERS = 750_000;
    private static final int MAX_PENDING_LOG_MESSAGES = 500;
    private static final int MAX_PENDING_LOG_CHARACTERS = 500_000;
    private static final int MAX_SINGLE_LOG_CHARACTERS = 100_000;
    private static final int LOG_BATCH_SIZE = 100;

    private final Object pendingLogLock = new Object();
    private final Deque<String> pendingLogs = new ArrayDeque<>();
    private int pendingLogCharacters;
    private long droppedLogCount;
    private boolean logFlushScheduled;
    private volatile boolean loggingClosed;

    /**
     * 记录模块日志并滚动到最新内容。
     *
     * @param level 日志级别
     * @param message 日志内容
     */
    @Override
    public void log(String level, String message) {
        if (loggingClosed) {
            return;
        }
        String formattedMessage = formatLogLine(level, message);
        boolean scheduleFlush = false;
        synchronized (pendingLogLock) {
            if (loggingClosed) {
                return;
            }
            pendingLogs.addLast(formattedMessage);
            pendingLogCharacters += formattedMessage.length();
            while ((pendingLogs.size() > MAX_PENDING_LOG_MESSAGES
                    || pendingLogCharacters > MAX_PENDING_LOG_CHARACTERS)
                    && pendingLogs.size() > 1) {
                String removed = pendingLogs.removeFirst();
                pendingLogCharacters -= removed.length();
                droppedLogCount++;
            }
            if (!logFlushScheduled) {
                logFlushScheduled = true;
                scheduleFlush = true;
            }
        }
        if (scheduleFlush) {
            scheduleLogFlush();
        }
    }

    /**
     * 清空当前模块日志区域。
     */
    @FXML
    public void handleClearLog() {
        clearPendingLogs();
        Runnable clear = () -> {
            TextArea area = getLogArea();
            if (area != null) {
                area.clear();
            }
        };
        if (Platform.isFxApplicationThread()) {
            clear.run();
        } else {
            Platform.runLater(clear);
        }
    }

    /**
     * 清理控制器资源，子类按需覆盖。
     */
    public void cleanup() {
        synchronized (pendingLogLock) {
            loggingClosed = true;
            pendingLogs.clear();
            pendingLogCharacters = 0;
            droppedLogCount = 0;
            logFlushScheduled = false;
        }
    }

    private String formatLogLine(String level, String message) {
        String value = message == null ? "" : message;
        if (value.length() > MAX_SINGLE_LOG_CHARACTERS) {
            int endIndex = MAX_SINGLE_LOG_CHARACTERS;
            if (Character.isHighSurrogate(value.charAt(endIndex - 1))
                    && Character.isLowSurrogate(value.charAt(endIndex))) {
                endIndex--;
            }
            value = value.substring(0, endIndex)
                    + "\n[日志过长，已截断；原始字符数: " + message.length() + "]";
        }
        return "[" + TimeUtils.getCurrentDateTime() + "]["
                + (level == null ? "INFO" : level) + "] " + value + "\n";
    }

    private void scheduleLogFlush() {
        try {
            Platform.runLater(this::flushPendingLogs);
        } catch (IllegalStateException e) {
            synchronized (pendingLogLock) {
                pendingLogs.clear();
                pendingLogCharacters = 0;
                droppedLogCount = 0;
                logFlushScheduled = false;
            }
        }
    }

    private void flushPendingLogs() {
        StringBuilder batch = new StringBuilder();
        long dropped;
        synchronized (pendingLogLock) {
            dropped = droppedLogCount;
            droppedLogCount = 0;
            int count = 0;
            while (count < LOG_BATCH_SIZE) {
                String line = pendingLogs.pollFirst();
                if (line == null) {
                    break;
                }
                pendingLogCharacters -= line.length();
                batch.append(line);
                count++;
            }
        }

        if (dropped > 0) {
            batch.insert(0, "[" + TimeUtils.getCurrentDateTime()
                    + "][WARN] 高负载期间已省略 " + dropped + " 条积压日志\n");
        }
        if (batch.length() > 0) {
            TextArea area = getLogArea();
            if (area != null && area.getScene() != null) {
                area.appendText(batch.toString());
                LogTextTrimmer.trimToMaxLines(area, MAX_LOG_LINES, 100);
                LogTextTrimmer.trimToMaxCharacters(
                        area, MAX_LOG_CHARACTERS, LOG_TRIM_TARGET_CHARACTERS);
            }
        }

        boolean scheduleNext;
        synchronized (pendingLogLock) {
            scheduleNext = !pendingLogs.isEmpty();
            if (!scheduleNext) {
                logFlushScheduled = false;
            }
        }
        if (scheduleNext) {
            scheduleLogFlush();
        }
    }

    private void clearPendingLogs() {
        synchronized (pendingLogLock) {
            pendingLogs.clear();
            pendingLogCharacters = 0;
            droppedLogCount = 0;
        }
    }
}
