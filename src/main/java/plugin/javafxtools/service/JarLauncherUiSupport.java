package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.FxTheme;
import plugin.javafxtools.util.LogTextTrimmer;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * JAR 启动器通用 UI 提示和日志辅助逻辑。
 *
 * @author wwj
 */
public class JarLauncherUiSupport {
    private static final int MAX_LOG_LINES = 800;
    private static final int MAX_LOG_CHARACTERS = 1_000_000;
    private static final int LOG_TRIM_TARGET_CHARACTERS = 750_000;
    private static final int MAX_PENDING_LOG_MESSAGES = 500;
    private static final int MAX_PENDING_LOG_CHARACTERS = 500_000;
    private static final int MAX_SINGLE_LOG_CHARACTERS = 100_000;
    private static final int LOG_BATCH_SIZE = 100;
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TextArea logArea;
    private final Consumer<Runnable> fxDispatcher;
    private final BooleanSupplier fxThreadChecker;
    private final Object pendingLogLock = new Object();
    private final Deque<String> pendingLogs = new ArrayDeque<>();
    private int pendingLogCharacters;
    private long droppedLogCount;
    private long flushToken;
    private boolean flushScheduled;
    private volatile boolean closed;

    /**
     * 创建 JAR 启动器 UI 辅助对象。
     *
     * @param logArea 日志文本区域
     */
    public JarLauncherUiSupport(TextArea logArea) {
        this(logArea, Platform::runLater, Platform::isFxApplicationThread);
    }

    JarLauncherUiSupport(TextArea logArea,
                         Consumer<Runnable> fxDispatcher,
                         BooleanSupplier fxThreadChecker) {
        this.logArea = logArea;
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher");
        this.fxThreadChecker = Objects.requireNonNull(fxThreadChecker, "fxThreadChecker");
    }

    /**
     * 追加日志。
     *
     * @param message 日志内容
     */
    public void appendLog(String message) {
        if (closed) {
            return;
        }
        String timestamp = LocalTime.now().format(LOG_TIME_FMT);
        String line = "[" + timestamp + "] " + limitMessage(message) + "\n";
        if (fxThreadChecker.getAsBoolean()) {
            appendLogOnFxThread(line);
            return;
        }

        long token = 0;
        synchronized (pendingLogLock) {
            if (closed) {
                return;
            }
            pendingLogs.addLast(line);
            pendingLogCharacters += line.length();
            while ((pendingLogs.size() > MAX_PENDING_LOG_MESSAGES
                    || pendingLogCharacters > MAX_PENDING_LOG_CHARACTERS)
                    && pendingLogs.size() > 1) {
                String removed = pendingLogs.removeFirst();
                pendingLogCharacters -= removed.length();
                droppedLogCount++;
            }
            if (!flushScheduled) {
                flushScheduled = true;
                token = ++flushToken;
            }
        }
        if (token != 0) {
            scheduleFlush(token);
        }
    }

    /**
     * 清除日志并使已经排队的旧刷新失效。
     */
    public void clearLogs() {
        synchronized (pendingLogLock) {
            pendingLogs.clear();
            pendingLogCharacters = 0;
            droppedLogCount = 0;
            flushScheduled = false;
            flushToken++;
        }
        Runnable clear = () -> {
            if (logArea != null) {
                logArea.clear();
            }
        };
        if (fxThreadChecker.getAsBoolean()) {
            clear.run();
        } else {
            try {
                fxDispatcher.accept(clear);
            } catch (IllegalStateException ignored) {
                // JavaFX 已关闭，无需清理不可见的界面。
            }
        }
    }

    /** Stops accepting logs and releases queued messages during controller teardown. */
    public void shutdown() {
        synchronized (pendingLogLock) {
            closed = true;
            pendingLogs.clear();
            pendingLogCharacters = 0;
            droppedLogCount = 0;
            flushScheduled = false;
            flushToken++;
        }
    }

    /**
     * 显示错误提示。
     *
     * @param message 错误内容
     */
    public void showError(String message) {
        Runnable showAlert = () -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            FxTheme.apply(alert);
            alert.setTitle("错误");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        };
        if (Platform.isFxApplicationThread()) {
            showAlert.run();
        } else {
            Platform.runLater(showAlert);
        }
    }

    /**
     * 确认是否终止端口占用进程。
     *
     * @param port 端口号
     * @return 用户是否确认
     */
    public boolean confirmKillProcessOnPort(int port) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        FxTheme.apply(alert);
        alert.setTitle("端口冲突");
        alert.setHeaderText("检测到端口 " + port + " 被占用");
        alert.setContentText("是否终止占用进程？");

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void appendLogOnFxThread(String line) {
        if (logArea == null) {
            return;
        }
        trimLogs();
        logArea.appendText(line);
        LogTextTrimmer.trimToMaxCharacters(
                logArea, MAX_LOG_CHARACTERS, LOG_TRIM_TARGET_CHARACTERS);
    }

    private void scheduleFlush(long token) {
        try {
            fxDispatcher.accept(() -> flushPendingLogs(token));
        } catch (IllegalStateException ignored) {
            synchronized (pendingLogLock) {
                if (token == flushToken) {
                    pendingLogs.clear();
                    pendingLogCharacters = 0;
                    droppedLogCount = 0;
                    flushScheduled = false;
                }
            }
        }
    }

    private void flushPendingLogs(long token) {
        StringBuilder batch = new StringBuilder();
        long dropped;
        synchronized (pendingLogLock) {
            if (closed || token != flushToken) {
                return;
            }
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
            batch.insert(0, "[" + LocalTime.now().format(LOG_TIME_FMT)
                    + "] [WARN] 高负载期间已省略 " + dropped + " 条日志\n");
        }
        if (batch.length() > 0) {
            appendLogOnFxThread(batch.toString());
        }

        long nextToken = 0;
        synchronized (pendingLogLock) {
            if (closed || token != flushToken) {
                return;
            }
            if (pendingLogs.isEmpty()) {
                flushScheduled = false;
            } else {
                nextToken = ++flushToken;
            }
        }
        if (nextToken != 0) {
            scheduleFlush(nextToken);
        }
    }

    private static String limitMessage(String message) {
        String value = message == null ? "" : message;
        if (value.length() <= MAX_SINGLE_LOG_CHARACTERS) {
            return value;
        }
        int endIndex = MAX_SINGLE_LOG_CHARACTERS;
        if (Character.isHighSurrogate(value.charAt(endIndex - 1))
                && Character.isLowSurrogate(value.charAt(endIndex))) {
            endIndex--;
        }
        return value.substring(0, endIndex)
                + "\n[日志过长，已截断；原始字符数: " + value.length() + "]";
    }

    private void trimLogs() {
        int lineCount = logArea.getParagraphs().size();
        if (lineCount < MAX_LOG_LINES) {
            return;
        }

        String text = logArea.getText();
        int linesToRemove = Math.max(100, lineCount - MAX_LOG_LINES + 1);
        int deleteIndex = 0;
        while (linesToRemove > 0) {
            int nextNewline = text.indexOf('\n', deleteIndex);
            if (nextNewline < 0) {
                break;
            }
            deleteIndex = nextNewline + 1;
            linesToRemove--;
        }
        if (deleteIndex > 0) {
            logArea.deleteText(0, deleteIndex);
        }
    }
}
