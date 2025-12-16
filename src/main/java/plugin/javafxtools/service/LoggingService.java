package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.TimeUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class LoggingService {
    private final List<TextArea> globalLogAreas = new ArrayList<>(); // 中央日志（所有模块可见）

    // 添加全局日志区域（如中央日志）
    public void addGlobalLogArea(TextArea logArea) {
        if (logArea != null && !globalLogAreas.contains(logArea)) {
            globalLogAreas.add(logArea);
        }
    }




    // 记录全局日志（中央）
    public void info(String message) {
        log("INFO", message, true);
    }



    // 记录错误日志（中央）
    public void error(String message) {
        log("ERROR", message, false);
    }

    private void log(String level, String message, boolean isGlobal) {
        String formattedMessage = String.format("[%s][%s] %s",
                TimeUtils.formatDateTime(new Date()), level, message);

        Platform.runLater(() -> {
            // 输出到全局区域（如中央日志）
            if (isGlobal) {
                for (TextArea area : globalLogAreas) {
                    safeAppend(area, formattedMessage);
                }
            }

        });
    }

    // 安全追加日志（避免NPE）
    private void safeAppend(TextArea area, String message) {
        if (area != null && area.getScene() != null) {
            area.appendText(message + "\n");
            area.setScrollTop(Double.MAX_VALUE); // 自动滚动到底部
        }
    }

    /**
     * 清空所有日志区域（全局+模块）
     */
    public void clearAll() {
        Platform.runLater(() -> {
            // 清空全局日志区域
            for (TextArea area : globalLogAreas) {
                if (area != null) {
                    area.clear();
                }
            }
        });
        System.out.println("所有日志区域已清空");
    }

    /**
     * 仅清空全局日志区域内容（不解除绑定）
     */
    public void clearGlobalLogs() {
        Platform.runLater(() -> {
            for (TextArea area : globalLogAreas) {
                if (area != null) area.clear();
            }
        });
    }

}