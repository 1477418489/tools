package plugin.javafxtools.model;

public class MemoReminder {
    private long id;
    private String content;
    private int interval;
    private TimeUnit unit;
    private int totalTimes;
    private int remainingTimes;
    private long nextTriggerEpochMillis;
    private boolean active;

    public MemoReminder() {
    }

    public MemoReminder(long id, String content, int interval, TimeUnit unit, int totalTimes) {
        this.id = id;
        this.content = content;
        this.interval = interval;
        this.unit = unit;
        this.totalTimes = totalTimes;
        this.remainingTimes = totalTimes <= 0 ? -1 : totalTimes;
        this.active = true;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getInterval() { return interval; }
    public void setInterval(int interval) { this.interval = interval; }

    public TimeUnit getUnit() { return unit; }
    public void setUnit(TimeUnit unit) { this.unit = unit; }

    public int getTotalTimes() { return totalTimes; }
    public void setTotalTimes(int totalTimes) { this.totalTimes = totalTimes; }

    public int getRemainingTimes() { return remainingTimes; }
    public void setRemainingTimes(int remainingTimes) { this.remainingTimes = remainingTimes; }

    public long getNextTriggerEpochMillis() { return nextTriggerEpochMillis; }
    public void setNextTriggerEpochMillis(long nextTriggerEpochMillis) { this.nextTriggerEpochMillis = nextTriggerEpochMillis; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public long intervalMillis() {
        return switch (unit) {
            case MINUTES -> interval * 60_000L;
            case HOURS -> interval * 3_600_000L;
            case DAYS -> interval * 86_400_000L;
        };
    }

    public String getDisplayRemaining() {
        return remainingTimes < 0 ? "∞" : String.valueOf(remainingTimes);
    }

    public String getDisplayInterval() {
        return interval + unit.getDisplayName();
    }

    public enum TimeUnit {
        MINUTES("分钟"),
        HOURS("小时"),
        DAYS("天");

        private final String displayName;

        TimeUnit(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
