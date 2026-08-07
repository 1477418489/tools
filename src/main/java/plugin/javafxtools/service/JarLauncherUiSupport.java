package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.FxTheme;
import plugin.javafxtools.util.LogTextTrimmer;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JAR 启动器通用 UI 提示和日志辅助逻辑。
 *
 * @author wwj
 */
public class JarLauncherUiSupport {
    private static final int MAX_LOG_LINES = 800;
    private static final int MAX_LOG_CHARACTERS = 1_000_000;
    private static final int LOG_TRIM_TARGET_CHARACTERS = 750_000;
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TextArea logArea;
    private final AtomicLong logGeneration = new AtomicLong();

    /**
     * 创建 JAR 启动器 UI 辅助对象。
     *
     * @param logArea 日志文本区域
     */
    public JarLauncherUiSupport(TextArea logArea) {
        this.logArea = logArea;
    }

    /**
     * 追加日志。
     *
     * @param message 日志内容
     */
    public void appendLog(String message) {
        String timestamp = LocalTime.now().format(LOG_TIME_FMT);
        String line = "[" + timestamp + "] " + message + "\n";
        long generation = logGeneration.get();
        if (Platform.isFxApplicationThread()) {
            if (generation == logGeneration.get()) {
                appendLogOnFxThread(line);
            }
        } else {
            try {
                Platform.runLater(() -> {
                    if (generation == logGeneration.get()) {
                        appendLogOnFxThread(line);
                    }
                });
            } catch (IllegalStateException ignored) {
                // JavaFX 已关闭，丢弃尚未显示的日志。
            }
        }
    }

    /**
     * 清除日志并使已经排队的旧刷新失效。
     */
    public void clearLogs() {
        logGeneration.incrementAndGet();
        Runnable clear = () -> {
            if (logArea != null) {
                logArea.clear();
            }
        };
        if (Platform.isFxApplicationThread()) {
            clear.run();
        } else {
            try {
                Platform.runLater(clear);
            } catch (IllegalStateException ignored) {
                // JavaFX 已关闭，无需清理不可见的界面。
            }
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
