package plugin.javafxtools.model;

import java.io.Serializable;

/**
 * 启动项工具中的应用配置项。
 */
public class AppInfo implements Serializable {
    /**
     * 可执行文件或脚本的完整路径。
     */
    public String appPath;

    /**
     * 启动后用于识别进程的进程名。
     */
    public String processName;

    /**
     * 供 Gson 反序列化使用的无参构造方法。
     */
    public AppInfo() {
    }

    /**
     * 创建启动项配置。
     *
     * @param appPath 应用路径
     * @param processName 进程名
     */
    public AppInfo(String appPath, String processName) {
        this.appPath = appPath;
        this.processName = processName;
    }

    /**
     * 获取应用路径。
     *
     * @return 应用路径
     */
    public String getAppPath() {
        return appPath;
    }

    /**
     * 设置应用路径。
     *
     * @param appPath 应用路径
     */
    public void setAppPath(String appPath) {
        this.appPath = appPath;
    }

    /**
     * 获取进程名。
     *
     * @return 进程名
     */
    public String getProcessName() {
        return processName;
    }

    /**
     * 设置进程名。
     *
     * @param processName 进程名
     */
    public void setProcessName(String processName) {
        this.processName = processName;
    }

    /**
     * 返回列表展示文本。
     *
     * @return 应用路径和可选进程名
     */
    @Override
    public String toString() {
        return appPath + (processName != null && !processName.isEmpty() ? " [" + processName + "]" : "");
    }
}
