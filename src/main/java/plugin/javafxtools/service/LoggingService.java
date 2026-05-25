package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.TimeUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * 全局日志服务，负责向主界面中央日志区域分发日志。
 */
public class LoggingService {
    /**
     * 单个日志区域保留的最大日志行数。
     */
    private static final int MAX_LOG_LINES = 800;

    /**
     * 中央日志区域弱引用列表，避免控制器释放后仍被服务持有。
     */
    private final List<WeakReference<TextArea>> globalLogAreas = new ArrayList<>();

    /**
     * 注册全局日志区域。
     *
     * @param logArea 要注册的日志区域
     */
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

    /**
     * 记录信息级别全局日志。
     *
     * @param message 日志内容
     */
    public void info(String message) {
        log("INFO", message);
    }

    /**
     * 记录错误级别全局日志。
     *
     * @param message 日志内容
     */
    public void error(String message) {
        log("ERROR", message);
    }

    /**
     * 向所有全局日志区域追加日志。
     *
     * @param level 日志级别
     * @param message 日志内容
     */
    private void log(String level, String message) {
        String formattedMessage = String.format("[%s][%s] %s",
                TimeUtils.formatDateTime(new Date()), level, message);

        Platform.runLater(() -> {
            synchronized (this) {
                cleanupReleasedAreas();
                for (WeakReference<TextArea> areaRef : globalLogAreas) {
                    safeAppend(areaRef.get(), formattedMessage);
                }
            }
        });
    }

    /**
     * 清理已经被释放的日志区域引用。
     */
    private synchronized void cleanupReleasedAreas() {
        Iterator<WeakReference<TextArea>> iterator = globalLogAreas.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().get() == null) {
                iterator.remove();
            }
        }
    }

    /**
     * 安全追加日志到单个文本区域。
     *
     * @param area 日志文本区域
     * @param message 已格式化的日志内容
     */
    private void safeAppend(TextArea area, String message) {
        if (area != null && area.getScene() != null) {
            trimLogLines(area);
            area.appendText(message + "\n");
            area.setScrollTop(Double.MAX_VALUE);
        }
    }

    /**
     * 控制日志区域行数，删除超过限制的旧日志。
     *
     * @param area 日志文本区域
     */
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
