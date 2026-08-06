package plugin.javafxtools.model;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 域名保活配置项。
 */
public class KeepAliveConfig {
    /**
     * 要保活的域名或 URL。
     */
    private String domain;

    /**
     * 是否启用该配置。
     */
    private boolean enabled;

    /**
     * 保活执行方式。
     */
    private KeepAliveMethod method;

    /**
     * 最小执行间隔。
     */
    private int minInterval;

    /**
     * 最大执行间隔。
     */
    private int maxInterval;

    /**
     * 间隔时间单位。
     */
    private IntervalUnit unit;

    /**
     * 供 JSON 反序列化使用的无参构造方法。
     */
    public KeepAliveConfig() {
    }

    /**
     * 创建指定方式的保活配置。
     *
     * @param domain 域名或 URL
     * @param enabled 是否启用
     * @param method 保活方式
     * @param minInterval 最小间隔
     * @param maxInterval 最大间隔
     * @param unit 间隔单位
     */
    public KeepAliveConfig(String domain, boolean enabled, KeepAliveMethod method,
                           int minInterval, int maxInterval, IntervalUnit unit) {
        this.domain = domain;
        this.enabled = enabled;
        this.method = Objects.requireNonNull(method, "method");
        this.minInterval = minInterval;
        this.maxInterval = maxInterval;
        this.unit = Objects.requireNonNull(unit, "unit");
    }

    /**
     * 获取域名或 URL。
     *
     * @return 域名或 URL
     */
    public String getDomain() { return domain; }

    /**
     * 设置域名或 URL。
     *
     * @param domain 域名或 URL
     */
    public void setDomain(String domain) { this.domain = domain; }

    /**
     * 判断配置是否启用。
     *
     * @return 启用状态
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置配置启用状态。
     *
     * @param enabled 启用状态
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取保活方式。
     *
     * @return 保活方式
     */
    public KeepAliveMethod getMethod() { return method; }

    /**
     * 设置保活方式。
     *
     * @param method 保活方式
     */
    public void setMethod(KeepAliveMethod method) { this.method = Objects.requireNonNull(method, "method"); }

    /**
     * 获取最小执行间隔。
     *
     * @return 最小执行间隔
     */
    public int getMinInterval() { return minInterval; }

    /**
     * 设置最小执行间隔。
     *
     * @param minInterval 最小执行间隔
     */
    public void setMinInterval(int minInterval) { this.minInterval = minInterval; }

    /**
     * 获取最大执行间隔。
     *
     * @return 最大执行间隔
     */
    public int getMaxInterval() { return maxInterval; }

    /**
     * 设置最大执行间隔。
     *
     * @param maxInterval 最大执行间隔
     */
    public void setMaxInterval(int maxInterval) { this.maxInterval = maxInterval; }

    /**
     * 获取间隔单位。
     *
     * @return 间隔单位
     */
    public IntervalUnit getUnit() { return unit; }

    /**
     * 设置间隔单位。
     *
     * @param unit 间隔单位
     */
    public void setUnit(IntervalUnit unit) { this.unit = Objects.requireNonNull(unit, "unit"); }

    /**
     * 在最大和最小间隔之间随机计算下次执行延迟。
     *
     * @return 下次执行延迟毫秒数
     */
    public long calculateRandomDelay() {
        if (minInterval <= 0 || maxInterval < minInterval || unit == null) {
            throw new IllegalStateException("保活间隔配置无效");
        }
        int randomInterval = (int) ThreadLocalRandom.current()
                .nextLong(minInterval, (long) maxInterval + 1L);
        return unit.toMillis(randomInterval);
    }
}
