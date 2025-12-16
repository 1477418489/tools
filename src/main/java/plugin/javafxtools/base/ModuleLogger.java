package plugin.javafxtools.base;

import javafx.scene.control.TextArea;

/**
 * 模块日志接口 - 提供统一的日志方法签名
 */
public interface ModuleLogger {
    /**
     * 记录信息级别日志
     * @param message 日志内容
     */
    default void info(String message) {
        log("INFO", message);
    }

    /**
     * 记录调试级别日志
     * @param message 日志内容
     */
    default void debug(String message) {
        log("DEBUG", message);
    }

    /**
     * 记录错误级别日志
     * @param message 日志内容
     */
    default void error(String message) {
        log("ERROR", message);
    }

    /**
     * 通用日志方法（子类必须实现）
     * @param level 日志级别
     * @param message 日志内容
     */
    void log(String level, String message);

    /**
     * 获取当前模块日志区域
     * @return 当前日志输出的TextArea
     */
    TextArea getLogArea();
}
