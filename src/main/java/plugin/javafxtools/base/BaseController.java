package plugin.javafxtools.base;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.TimeUtils;

/**
 * 控制器基类 - 封装通用的日志、清空日志、清理资源逻辑
 */
public abstract class BaseController implements ModuleLogger {
    /**
     * 默认模块日志最大保留行数。
     */
    private static final int MAX_LOG_LINES = 800;

    /**
     * 记录模块日志并滚动到最新内容。
     *
     * @param level 日志级别
     * @param message 日志内容
     */
    @Override
    public void log(String level, String message) {
        String formattedMessage = String.format("[%s][%s] %s",
                TimeUtils.getCurrentDateTime(), level, message);
        Platform.runLater(() -> {
            TextArea area = getLogArea();
            if (area != null && area.getScene() != null) {
                trimLogLines(area);
                area.appendText(formattedMessage + "\n");
                area.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    /**
     * 裁剪超过上限的旧日志，避免 TextArea 长时间运行后拖慢界面。
     *
     * @param area 日志文本区域
     */
    private void trimLogLines(TextArea area) {
        int lineCount = area.getParagraphs().size();
        if (lineCount < MAX_LOG_LINES) {
            return;
        }

        String text = area.getText();
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
            area.deleteText(0, deleteIndex);
        }
    }

    /**
     * 清空当前模块日志区域。
     */
    @FXML
    public void handleClearLog() {
        Platform.runLater(() -> {
            TextArea area = getLogArea();
            if (area != null) {
                area.clear();
            }
        });
    }

    /**
     * 清理控制器资源，子类按需覆盖。
     */
    public void cleanup() {
        // 子类按需覆盖
    }
}
