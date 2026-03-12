package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.TimeUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

public class LoggingService {
    private static final int MAX_LOG_LINES = 800;
    private final List<WeakReference<TextArea>> globalLogAreas = new ArrayList<>();

    // 添加全局日志区域（如中央日志）
    public synchronized void addGlobalLogArea(TextArea logArea) {
        if (logArea == null) {
            return;
        }
        cleanupReleasedAreas();
        boolean exists = globalLogAreas.stream()
                .map(WeakReference::get)
                .anyMatch(area -> area == logArea);
        if (!exists) {
            globalLogAreas.add(new WeakReference<>(logArea));
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
            if (isGlobal) {
                synchronized (this) {
                    cleanupReleasedAreas();
                    for (WeakReference<TextArea> areaRef : globalLogAreas) {
                        safeAppend(areaRef.get(), formattedMessage);
                    }
                }
            }
        });
    }

    private synchronized void cleanupReleasedAreas() {
        Iterator<WeakReference<TextArea>> iterator = globalLogAreas.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().get() == null) {
                iterator.remove();
            }
        }
    }

    // 安全追加日志（避免NPE）
    private void safeAppend(TextArea area, String message) {
        if (area != null && area.getScene() != null) {
            trimLogLines(area);
            area.appendText(message + "\n");
            area.setScrollTop(Double.MAX_VALUE); // 自动滚动到底部
        }
    }

    private void trimLogLines(TextArea area) {
        String text = area.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        int lineCount = area.getParagraphs().size();
        if (lineCount < MAX_LOG_LINES) {
            return;
        }

        int removeLines = Math.max(100, lineCount - MAX_LOG_LINES + 1);
        int index = 0;
        while (removeLines > 0) {
            int newlineIndex = text.indexOf('\n', index);
            if (newlineIndex < 0) {
                break;
            }
            index = newlineIndex + 1;
            removeLines--;
        }

        if (index > 0) {
            area.deleteText(0, index);
        }
    }

    /**
     * 清空所有日志区域（全局+模块）
     */
    public void clearAll() {
        Platform.runLater(() -> {
            synchronized (this) {
                cleanupReleasedAreas();
                for (WeakReference<TextArea> areaRef : globalLogAreas) {
                    TextArea area = areaRef.get();
                    if (area != null) {
                        area.clear();
                    }
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
            synchronized (this) {
                cleanupReleasedAreas();
                for (WeakReference<TextArea> areaRef : globalLogAreas) {
                    TextArea area = areaRef.get();
                    if (area != null) {
                        area.clear();
                    }
                }
            }
        });
    }
}
