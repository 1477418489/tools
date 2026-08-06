package plugin.javafxtools.service;

import javafx.scene.control.TextArea;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.KeepAliveConfig;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 增强版域名保活服务，支持多域名独立配置、随机间隔和 HTTP/Ping 两种保活方式。
 */
public class EnhancedKeepAliveService implements ModuleLogger {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "KeepAlive-Worker");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
    );

    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final Set<String> activeDomains = ConcurrentHashMap.newKeySet();
    private final Map<String, KeepAliveConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, Long> scheduleVersions = new HashMap<>();
    private final AtomicLong versionSequence = new AtomicLong();
    private final Object scheduleLock = new Object();
    private final KeepAliveLogBuffer logBuffer;
    private final KeepAliveProbeService probeService;
    private volatile boolean closed;

    public EnhancedKeepAliveService(TextArea logArea) {
        this.logBuffer = new KeepAliveLogBuffer(logArea);
        this.probeService = new KeepAliveProbeService(this::info, this::warn, this::error);
        info("EnhancedKeepAliveService 初始化完成");
    }

    public void updateConfigs(List<KeepAliveConfig> configList) {
        if (configList == null || closed) {
            return;
        }

        debug("批量更新配置，共 " + configList.size() + " 条");

        Set<String> domainsToStop = new HashSet<>(activeDomains);
        configs.clear();
        for (KeepAliveConfig config : configList) {
            if (config != null) {
                configs.put(config.getDomain(), config);
                if (config.isEnabled()) {
                    domainsToStop.remove(config.getDomain());
                }
            }
        }

        for (String domain : domainsToStop) {
            stopDomain(domain);
        }
        startAll();
    }

    public void startDomain(String domain) {
        if (domain == null || domain.trim().isEmpty()) {
            return;
        }

        KeepAliveConfig config = configs.get(domain);
        if (config == null || !config.isEnabled()) {
            return;
        }

        long initialDelay = ThreadLocalRandom.current().nextLong(5000L);
        synchronized (scheduleLock) {
            if (closed) {
                return;
            }
            cancelScheduledTaskLocked(domain);
            long version = versionSequence.incrementAndGet();
            scheduleVersions.put(domain, version);
            activeDomains.add(domain);
            scheduleLocked(domain, version, initialDelay);
        }

        info("启动: " + probeService.getDomainName(domain));
    }

    private void executeDomainPing(String domain, long expectedVersion) {
        KeepAliveConfig config;
        synchronized (scheduleLock) {
            if (!isCurrentScheduleLocked(domain, expectedVersion)) {
                return;
            }
            config = configs.get(domain);
        }
        if (config == null || !config.isEnabled()) {
            return;
        }

        try {
            probeService.pingDomain(config);
        } catch (RuntimeException e) {
            error("保活任务执行失败: " + probeService.getDomainName(domain)
                    + " - " + e.getMessage());
        }
        scheduleNextPing(domain, expectedVersion);
    }

    private void scheduleNextPing(String domain, long expectedVersion) {
        KeepAliveConfig config = configs.get(domain);
        if (config == null || !config.isEnabled()) {
            return;
        }

        long delay;
        try {
            delay = config.calculateRandomDelay();
        } catch (RuntimeException e) {
            error("无法安排下次保活任务: " + probeService.getDomainName(domain)
                    + " - " + e.getMessage());
            stopDomain(domain);
            return;
        }

        synchronized (scheduleLock) {
            if (!isCurrentScheduleLocked(domain, expectedVersion)) {
                return;
            }
            scheduleLocked(domain, expectedVersion, delay);
        }

        if (delay < 300000) { // 5分钟内的任务才记录
            debug("下次访问 [" + probeService.getDomainName(domain) + "] 在 " +
                    TimeUnit.MILLISECONDS.toMinutes(delay) + " 分钟后");
        }
    }

    public void stopDomain(String domain) {
        if (domain == null) {
            return;
        }

        boolean taskCancelled;
        boolean wasActive;
        synchronized (scheduleLock) {
            scheduleVersions.remove(domain);
            taskCancelled = cancelScheduledTaskLocked(domain);
            wasActive = activeDomains.remove(domain);
        }
        if (taskCancelled) {
            debug("已取消计划任务: " + probeService.getDomainName(domain));
        }
        if (wasActive) {
            info("停止: " + probeService.getDomainName(domain));
        }
    }

    public void startAll() {
        int count = 0;
        for (KeepAliveConfig config : configs.values()) {
            if (config.isEnabled()) {
                startDomain(config.getDomain());
                count++;
            }
        }
        if (count > 0) {
            info("已启动 " + count + " 个域名");
        }
    }

    public void cleanup() {
        if (closed) {
            return;
        }
        info("清理保活服务资源...");

        synchronized (scheduleLock) {
            if (closed) {
                return;
            }
            closed = true;
            scheduleVersions.clear();
            for (ScheduledFuture<?> future : scheduledFutures.values()) {
                if (future != null) {
                    future.cancel(true);
                }
            }
            scheduledFutures.clear();
            activeDomains.clear();
            scheduler.shutdownNow();
        }
        logBuffer.shutdown();
        configs.clear();
    }

    private void scheduleLocked(String domain, long version, long delay) {
        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeDomainPing(domain, version), delay, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = scheduledFutures.put(domain, future);
        if (previous != null && previous != future) {
            previous.cancel(false);
        }
    }

    private boolean cancelScheduledTaskLocked(String domain) {
        ScheduledFuture<?> future = scheduledFutures.remove(domain);
        return future != null && future.cancel(false);
    }

    private boolean isCurrentScheduleLocked(String domain, long expectedVersion) {
        return !closed
                && activeDomains.contains(domain)
                && Objects.equals(scheduleVersions.get(domain), expectedVersion);
    }

    /**
     * 记录保活模块日志。
     *
     * @param level 日志级别
     * @param message 日志内容
     */
    @Override
    public void log(String level, String message) {
        logBuffer.log(level, message);
    }

    /**
     * 获取日志输出区域。
     *
     * @return 日志输出区域
     */
    @Override
    public TextArea getLogArea() {
        return logBuffer.getLogArea();
    }

    /**
     * 记录信息日志。
     *
     * @param message 日志内容
     */
    @Override
    public void info(String message) {
        log("INFO", message);
    }

    /**
     * 记录错误日志。
     *
     * @param message 日志内容
     */
    @Override
    public void error(String message) {
        log("ERROR", message);
    }

    /**
     * 记录警告日志。
     *
     * @param message 日志内容
     */
    public void warn(String message) {
        log("WARN", message);
    }

    /**
     * 记录调试日志，队列积压时自动降噪。
     *
     * @param message 日志内容
     */
    @Override
    public void debug(String message) {
        logBuffer.debug(message);
    }

    /**
     * 获取当前活跃的域名数量
     */
    public int getActiveDomainCount() {
        return activeDomains.size();
    }

    /**
     * 检查域名是否正在保活
     */
    public boolean isDomainActive(String domain) {
        return activeDomains.contains(domain);
    }

    /**
     * 获取域名的当前配置
     */
    public KeepAliveConfig getDomainConfig(String domain) {
        return configs.get(domain);
    }

    /**
     * 手动触发一次域名访问（用于测试）
     */
    public void testPingDomain(String domain) {
        synchronized (scheduleLock) {
            KeepAliveConfig config = configs.get(domain);
            if (config == null || closed) {
                return;
            }
            scheduler.execute(() -> probeService.pingDomain(config));
        }
    }

    /**
     * 获取服务状态信息
     */
    public String getServiceStatus() {
        int activeCount = activeDomains.size();
        int configCount = configs.size();
        int enabledCount = (int) configs.values().stream()
                .filter(KeepAliveConfig::isEnabled)
                .count();

        return String.format("配置: %d, 启用: %d, 运行中: %d",
                configCount, enabledCount, activeCount);
    }
}
