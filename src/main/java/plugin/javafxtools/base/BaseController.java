package plugin.javafxtools.base;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.LogTextTrimmer;
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
                LogTextTrimmer.trimToMaxLines(area, MAX_LOG_LINES, 100);
                area.appendText(formattedMessage + "\n");
                area.setScrollTop(Double.MAX_VALUE);
            }
        });
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
