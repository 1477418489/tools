package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * JAR 启动器通用 UI 提示和日志辅助逻辑。
 *
 * @author wwj
 */
public class JarLauncherUiSupport {
    private static final int MAX_LOG_LINES = 800;
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TextArea logArea;

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
        if (Platform.isFxApplicationThread()) {
            appendLogOnFxThread(line);
        } else {
            Platform.runLater(() -> appendLogOnFxThread(line));
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
            alert.setTitle("错误");
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
        logArea.setScrollTop(Double.MAX_VALUE);
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
