package plugin.javafxtools.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class KeepAliveConfig {
    private String domain;
    private boolean enabled;
    private int minInterval;  // 最小间隔
    private int maxInterval;  // 最大间隔（用于随机）
    private TimeUnit unit;

    public KeepAliveConfig() {
        // Jackson需要无参构造函数
    }

    @JsonCreator
    public KeepAliveConfig(
            @JsonProperty("domain") String domain,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("minInterval") int minInterval,
            @JsonProperty("maxInterval") int maxInterval,
            @JsonProperty("unit") TimeUnit unit) {
        this.domain = domain;
        this.enabled = enabled;
        this.minInterval = minInterval;
        this.maxInterval = maxInterval;
        this.unit = unit;
    }

    // Getters and Setters
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMinInterval() { return minInterval; }
    public void setMinInterval(int minInterval) { this.minInterval = minInterval; }

    public int getMaxInterval() { return maxInterval; }
    public void setMaxInterval(int maxInterval) { this.maxInterval = maxInterval; }

    public TimeUnit getUnit() { return unit; }
    public void setUnit(TimeUnit unit) { this.unit = unit; }

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

    /**
     * 计算随机的下次执行时间（毫秒）
     */
    public long calculateRandomDelay() {
        int min = Math.min(minInterval, maxInterval);
        int max = Math.max(minInterval, maxInterval);
        int randomInterval = min + (int)(Math.random() * (max - min + 1));

        return convertToMillis(randomInterval, unit);
    }

    /**
     * 转换为毫秒
     */
    private long convertToMillis(int value, TimeUnit unit) {
        switch (unit) {
            case MINUTES:
                return value * 60L * 1000L;
            case HOURS:
                return value * 60L * 60L * 1000L;
            case DAYS:
                return value * 24L * 60L * 60L * 1000L;
            default:
                return value * 60L * 1000L;
        }
    }
}