package plugin.javafxtools.model;

/**
 * 间隔时间单位枚举，供域名保活和备忘提醒共用。
 */
public enum IntervalUnit {
    /**
     * 分钟。
     */
    MINUTES("分钟", 60_000L),

    /**
     * 小时。
     */
    HOURS("小时", 3_600_000L),

    /**
     * 天。
     */
    DAYS("天", 86_400_000L);

    /**
     * 界面展示名称。
     */
    private final String displayName;

    /**
     * 单位对应的毫秒数。
     */
    private final long millis;

    /**
     * 创建时间单位。
     *
     * @param displayName 展示名称
     * @param millis 单位毫秒数
     */
    IntervalUnit(String displayName, long millis) {
        this.displayName = displayName;
        this.millis = millis;
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
     * 获取单个单位对应的毫秒数。
     *
     * @return 毫秒数
     */
    public long toMillis() {
        return millis;
    }

    /**
     * 将指定数量的当前单位转换为毫秒。
     *
     * @param value 单位数量
     * @return 毫秒数
     */
    public long toMillis(int value) {
        return value * millis;
    }

    /**
     * 返回用于 ComboBox 展示的文本。
     *
     * @return 展示名称
     */
    @Override
    public String toString() {
        return displayName;
    }
}
