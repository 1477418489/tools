package plugin.javafxtools.model;

import java.io.Serializable;

public class AppInfo implements Serializable {
    public String appPath;
    public String processName;

    public AppInfo() {
    } // 无参构造方法

    public AppInfo(String appPath, String processName) {
        this.appPath = appPath;
        this.processName = processName;
    }

    public String getAppPath() {
        return appPath;
    }

    public void setAppPath(String appPath) {
        this.appPath = appPath;
    }

    public String getProcessName() {
        return processName;
    }

    public void setProcessName(String processName) {
        this.processName = processName;
    }

    @Override
    public String toString() {
        return appPath + (processName != null && !processName.isEmpty() ? " [" + processName + "]" : "");
    }
}