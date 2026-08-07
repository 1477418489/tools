package plugin.javafxtools.model;

/**
 * JAR 项目的可视运行状态。
 */
public enum JarProjectRuntimeStatus {
    UNKNOWN("未检查", "project-status-unknown"),
    CHECKING("检查中", "project-status-checking"),
    STARTING("启动中", "project-status-checking"),
    STOPPING("停止中", "project-status-checking"),
    RUNNING("运行中", "project-status-running"),
    STOPPED("已停止", "project-status-stopped"),
    CONFLICT("端口占用", "project-status-conflict"),
    ERROR("异常", "project-status-error");

    private final String label;
    private final String styleClass;

    JarProjectRuntimeStatus(String label, String styleClass) {
        this.label = label;
        this.styleClass = styleClass;
    }

    public String label() {
        return label;
    }

    public String styleClass() {
        return styleClass;
    }

    public boolean isBusy() {
        return this == CHECKING || this == STARTING || this == STOPPING;
    }

    public boolean isTerminal() {
        return this == RUNNING || this == STOPPED || this == CONFLICT || this == ERROR;
    }
}
