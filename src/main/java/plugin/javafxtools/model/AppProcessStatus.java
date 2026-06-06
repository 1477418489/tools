package plugin.javafxtools.model;

/**
 * 启动项进程状态缓存项。
 *
 * @author wwj
 */
public class AppProcessStatus {
    /**
     * 最近一次检查到的运行状态。
     */
    private final boolean running;

    /**
     * 缓存创建时间戳。
     */
    private final long timestamp;

    /**
     * 进程检查耗时。
     */
    private final long checkDuration;

    /**
     * 创建进程状态缓存项。
     *
     * @param running 是否运行中
     * @param checkDuration 检查耗时
     */
    public AppProcessStatus(boolean running, long checkDuration) {
        this.running = running;
        this.timestamp = System.currentTimeMillis();
        this.checkDuration = checkDuration;
    }

    /**
     * 判断缓存项是否过期。
     *
     * @param maxAge 最大缓存时长
     * @return 是否过期
     */
    public boolean isExpired(long maxAge) {
        return System.currentTimeMillis() - timestamp > maxAge;
    }

    /**
     * 获取运行状态。
     *
     * @return 运行中返回 true
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取检查耗时。
     *
     * @return 检查耗时
     */
    public long getCheckDuration() {
        return checkDuration;
    }
}
