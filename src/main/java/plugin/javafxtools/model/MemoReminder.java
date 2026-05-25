package plugin.javafxtools.model;

/**
 * 备忘提醒配置项。
 */
public class MemoReminder {
    /**
     * 提醒唯一标识。
     */
    private long id;

    /**
     * 提醒内容。
     */
    private String content;

    /**
     * 提醒间隔数量。
     */
    private int interval;

    /**
     * 提醒间隔单位。
     */
    private IntervalUnit unit;

    /**
     * 总提醒次数，0 或负数表示不限次数。
     */
    private int totalTimes;

    /**
     * 剩余提醒次数，-1 表示不限次数。
     */
    private int remainingTimes;

    /**
     * 下次触发时间戳，单位为毫秒。
     */
    private long nextTriggerEpochMillis;

    /**
     * 是否处于启用状态。
     */
    private boolean active;

    /**
     * 供 JSON 反序列化使用的无参构造方法。
     */
    public MemoReminder() {
    }

    /**
     * 创建备忘提醒。
     *
     * @param id 提醒唯一标识
     * @param content 提醒内容
     * @param interval 间隔数量
     * @param unit 间隔单位
     * @param totalTimes 总提醒次数
     */
    public MemoReminder(long id, String content, int interval, IntervalUnit unit, int totalTimes) {
        this.id = id;
        this.content = content;
        this.interval = interval;
        this.unit = unit;
        this.totalTimes = totalTimes;
        this.remainingTimes = totalTimes <= 0 ? -1 : totalTimes;
        this.active = true;
    }

    /**
     * 获取提醒唯一标识。
     *
     * @return 提醒唯一标识
     */
    public long getId() { return id; }

    /**
     * 设置提醒唯一标识。
     *
     * @param id 提醒唯一标识
     */
    public void setId(long id) { this.id = id; }

    /**
     * 获取提醒内容。
     *
     * @return 提醒内容
     */
    public String getContent() { return content; }

    /**
     * 设置提醒内容。
     *
     * @param content 提醒内容
     */
    public void setContent(String content) { this.content = content; }

    /**
     * 获取提醒间隔数量。
     *
     * @return 提醒间隔数量
     */
    public int getInterval() { return interval; }

    /**
     * 设置提醒间隔数量。
     *
     * @param interval 提醒间隔数量
     */
    public void setInterval(int interval) { this.interval = interval; }

    /**
     * 获取提醒间隔单位。
     *
     * @return 提醒间隔单位
     */
    public IntervalUnit getUnit() { return unit; }

    /**
     * 设置提醒间隔单位。
     *
     * @param unit 提醒间隔单位
     */
    public void setUnit(IntervalUnit unit) { this.unit = unit; }

    /**
     * 获取总提醒次数。
     *
     * @return 总提醒次数
     */
    public int getTotalTimes() { return totalTimes; }

    /**
     * 设置总提醒次数。
     *
     * @param totalTimes 总提醒次数
     */
    public void setTotalTimes(int totalTimes) { this.totalTimes = totalTimes; }

    /**
     * 获取剩余提醒次数。
     *
     * @return 剩余提醒次数
     */
    public int getRemainingTimes() { return remainingTimes; }

    /**
     * 设置剩余提醒次数。
     *
     * @param remainingTimes 剩余提醒次数
     */
    public void setRemainingTimes(int remainingTimes) { this.remainingTimes = remainingTimes; }

    /**
     * 获取下次触发时间戳。
     *
     * @return 下次触发时间戳，单位为毫秒
     */
    public long getNextTriggerEpochMillis() { return nextTriggerEpochMillis; }

    /**
     * 设置下次触发时间戳。
     *
     * @param nextTriggerEpochMillis 下次触发时间戳，单位为毫秒
     */
    public void setNextTriggerEpochMillis(long nextTriggerEpochMillis) { this.nextTriggerEpochMillis = nextTriggerEpochMillis; }

    /**
     * 判断提醒是否启用。
     *
     * @return 启用状态
     */
    public boolean isActive() { return active; }

    /**
     * 设置提醒启用状态。
     *
     * @param active 启用状态
     */
    public void setActive(boolean active) { this.active = active; }

    /**
     * 计算当前提醒间隔对应的毫秒数。
     *
     * @return 间隔毫秒数
     */
    public long intervalMillis() {
        return unit.toMillis(interval);
    }

    /**
     * 获取用于表格显示的剩余次数。
     *
     * @return 剩余次数展示文本
     */
    public String getDisplayRemaining() {
        return remainingTimes < 0 ? "∞" : String.valueOf(remainingTimes);
    }

    /**
     * 获取用于表格显示的提醒间隔。
     *
     * @return 间隔展示文本
     */
    public String getDisplayInterval() {
        return interval + unit.getDisplayName();
    }
}
