package plugin.javafxtools.model;

/**
 * 域名保活执行方式。
 */
public enum KeepAliveMethod {
    /**
     * 通过 HTTP 请求保活。
     */
    HTTP("HTTP访问"),

    /**
     * 通过 Windows ping 命令保活。
     */
    PING("Ping");

    /**
     * 界面展示名称。
     */
    private final String displayName;

    /**
     * 创建保活方式枚举。
     *
     * @param displayName 展示名称
     */
    KeepAliveMethod(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 获取界面展示名称。
     *
     * @return 展示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 返回 ComboBox 展示文本。
     *
     * @return 展示名称
     */
    @Override
    public String toString() {
        return displayName;
    }
}
