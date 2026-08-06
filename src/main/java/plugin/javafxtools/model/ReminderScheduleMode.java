package plugin.javafxtools.model;

/**
 * 备忘提醒的调度方式。
 */
public enum ReminderScheduleMode {
    /**
     * 按固定间隔重复提醒。
     */
    INTERVAL,

    /**
     * 在指定日期和时间进行一次性提醒。
     */
    AT_TIME
}
