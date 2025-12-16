package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.KeepAliveConfig;
import plugin.javafxtools.util.TimeUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/**
 * 增强版域名保活服务
 * 支持多域名独立配置和多种时间单位
 */
public class EnhancedKeepAliveService implements ModuleLogger {
    private final Map<String, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>(); // 添加这个字段
    private final Map<String, KeepAliveConfig> configs = new ConcurrentHashMap<>();
    private TextArea logArea;

    // 配置常量
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 10000;
    private static final int TERMINATION_TIMEOUT = 2; // 减少关闭等待时间
    private static final int LOG_LINE_LIMIT = 500;
    private static final int LOG_CLEAN_BATCH_SIZE = 100;

    // 日志批处理
    private final Queue<String> logQueue = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService logExecutor;
    private volatile boolean isLogProcessing = false;
    private static final int LOG_BATCH_SIZE = 10;
    private static final long LOG_FLUSH_INTERVAL = 100;

    public EnhancedKeepAliveService(TextArea logArea) {
        this.logArea = logArea;

        // 初始化日志批处理执行器（使用守护线程）
        logExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true); // 设置为守护线程
            t.setName("KeepAlive-LogProcessor");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        // 定期刷新日志
        logExecutor.scheduleAtFixedRate(this::flushLogs,
                LOG_FLUSH_INTERVAL, LOG_FLUSH_INTERVAL, TimeUnit.MILLISECONDS);

        info("EnhancedKeepAliveService 初始化完成");
    }

    /**
     * 批量更新配置
     */
    public void updateConfigs(List<KeepAliveConfig> configList) {
        if (configList == null) {
            return;
        }

        debug("批量更新配置，共 " + configList.size() + " 条");

        // 收集要停止的域名
        Set<String> domainsToStop = new HashSet<>(schedulers.keySet());

        // 更新配置
        configs.clear();
        for (KeepAliveConfig config : configList) {
            if (config != null) {
                configs.put(config.getDomain(), config);
                domainsToStop.remove(config.getDomain());
            }
        }

        // 停止不再存在的域名的任务
        for (String domain : domainsToStop) {
            stopDomain(domain);
        }

        // 启动所有启用的配置（已存在的会自动更新）
        startAll();
    }

    /**
     * 启动特定域名的保活服务
     */
    public void startDomain(String domain) {
        if (domain == null || domain.trim().isEmpty()) {
            return;
        }

        KeepAliveConfig config = configs.get(domain);
        if (config == null || !config.isEnabled()) {
            return;
        }

        // 如果已有任务在运行，先停止
        stopDomain(domain);

        // 创建调度器（使用守护线程）
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true); // 关键：设置为守护线程
            t.setName("KeepAlive-" + getDomainName(domain));
            t.setPriority(Thread.MIN_PRIORITY); // 降低优先级
            return t;
        });

        schedulers.put(domain, scheduler);

        // 安排第一次执行（使用随机延迟开始，避免所有任务同时启动）
        long initialDelay = (long)(Math.random() * 5000); // 0-5秒随机延迟
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                executeDomainPing(domain);
            } catch (Exception e) {
                // 静默处理，避免异常传播
            }
        }, initialDelay, TimeUnit.MILLISECONDS);

        scheduledFutures.put(domain, future); // 保存Future引用

        info("启动: " + getDomainName(domain));
    }

    /**
     * 执行域名ping任务
     */
    private void executeDomainPing(String domain) {
        KeepAliveConfig config = configs.get(domain);
        if (config == null || !config.isEnabled()) {
            return;
        }

        // 执行ping
        pingDomain(domain);

        // 安排下一次执行
        scheduleNextPing(domain);
    }

    /**
     * 安排下一次ping任务
     */
    private void scheduleNextPing(String domain) {
        KeepAliveConfig config = configs.get(domain);
        if (config == null || !config.isEnabled()) {
            return;
        }

        ScheduledExecutorService scheduler = schedulers.get(domain);
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }

        // 计算下一次执行延迟
        long delay = config.calculateRandomDelay();

        // 取消已存在的任务
        ScheduledFuture<?> existingFuture = scheduledFutures.get(domain);
        if (existingFuture != null) {
            existingFuture.cancel(false);
        }

        // 安排新任务
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                executeDomainPing(domain);
            } catch (Exception e) {
                // 静默处理
            }
        }, delay, TimeUnit.MILLISECONDS);

        scheduledFutures.put(domain, future); // 更新Future引用

        // 减少调试日志频率
        if (delay < 300000) { // 5分钟内的任务才记录
            debug("下次访问 [" + getDomainName(domain) + "] 在 " +
                    TimeUnit.MILLISECONDS.toMinutes(delay) + " 分钟后");
        }
    }

    /**
     * 停止特定域名的保活服务
     */
    public void stopDomain(String domain) {
        if (domain == null) {
            return;
        }

        // 取消计划任务
        ScheduledFuture<?> future = scheduledFutures.remove(domain);
        if (future != null) {
            future.cancel(false);
            debug("已取消计划任务: " + getDomainName(domain));
        }

        // 关闭调度器
        ScheduledExecutorService scheduler = schedulers.remove(domain);
        if (scheduler != null) {
            shutdownScheduler(scheduler);
            info("停止: " + getDomainName(domain));
        }
    }

    /**
     * 优雅关闭调度器
     */
    private void shutdownScheduler(ScheduledExecutorService scheduler) {
        try {
            // 先尝试优雅关闭
            scheduler.shutdown();

            // 等待很短时间
            if (!scheduler.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS)) {
                // 超时后强制关闭
                List<Runnable> pendingTasks = scheduler.shutdownNow();
                if (!pendingTasks.isEmpty()) {
                    debug("强制关闭，有 " + pendingTasks.size() + " 个任务未执行");
                }
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 启动所有启用的域名保活服务
     */
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

    /**
     * 停止所有域名保活服务
     */
    public void stopAll() {
        debug("停止所有域名保活服务...");

        // 先取消所有计划任务
        for (ScheduledFuture<?> future : scheduledFutures.values()) {
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }
        scheduledFutures.clear();

        // 关闭所有调度器
        for (ScheduledExecutorService scheduler : schedulers.values()) {
            if (scheduler != null) {
                shutdownScheduler(scheduler);
            }
        }
        schedulers.clear();

        debug("所有域名保活服务已停止");
    }

    /**
     * 快速停止所有域名任务（不等待）
     */
    public void stopAllQuickly() {
        debug("快速停止所有域名任务...");

        // 立即取消所有计划任务
        for (ScheduledFuture<?> future : scheduledFutures.values()) {
            if (future != null) {
                future.cancel(true); // 强制中断
            }
        }
        scheduledFutures.clear();

        // 立即关闭所有调度器
        for (ScheduledExecutorService scheduler : schedulers.values()) {
            if (scheduler != null) {
                List<Runnable> pendingTasks = scheduler.shutdownNow();
                if (!pendingTasks.isEmpty()) {
                    debug("强制关闭，有 " + pendingTasks.size() + " 个任务被丢弃");
                }
            }
        }
        schedulers.clear();

        debug("快速停止完成");
    }

    /**
     * 强制关闭（立即停止所有）
     */
    public void forceShutdown() {
        debug("强制关闭保活服务...");

        // 1. 立即停止所有计划任务
        for (ScheduledFuture<?> future : scheduledFutures.values()) {
            if (future != null) {
                future.cancel(true);
            }
        }
        scheduledFutures.clear();

        // 2. 立即关闭所有调度器
        for (ScheduledExecutorService scheduler : schedulers.values()) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
        schedulers.clear();

        // 3. 停止日志处理器
        if (logExecutor != null) {
            logExecutor.shutdownNow();
        }

        // 4. 清空所有数据
        configs.clear();
        logQueue.clear();
        logArea = null;

        debug("强制关闭完成");
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        info("开始清理保活服务资源...");
        long startTime = System.currentTimeMillis();

        // 使用快速停止
        stopAllQuickly();

        // 清理日志处理器
        if (logExecutor != null) {
            try {
                logExecutor.shutdown();
                if (!logExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    logExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 清空所有数据
        configs.clear();
        logQueue.clear();
        scheduledFutures.clear();
        schedulers.clear();

        // 清理UI引用
        logArea = null;

        long endTime = System.currentTimeMillis();
        System.out.println("保活服务清理完成，耗时: " + (endTime - startTime) + "ms");
    }

    /**
     * 访问域名以保持其活跃状态
     */
    private void pingDomain(String domain) {
        KeepAliveConfig config = configs.get(domain);
        if (config == null || !config.isEnabled()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            // 随机用户代理
            String[] userAgents = {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/121.0"
            };

            String userAgent = userAgents[(int)(Math.random() * userAgents.length)];

            HttpURLConnection connection = null;
            try {
                URL url = new URL(domain);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);

                // 简化请求头
                connection.setRequestProperty("Accept", "text/html");
                connection.setRequestProperty("Cache-Control", "no-cache");

                // 更小的随机延迟
                Thread.sleep((long)(Math.random() * 1000));

                int responseCode = connection.getResponseCode();
                long responseTime = System.currentTimeMillis() - startTime;

                // 成功时记录简略日志
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    info("✓ " + getDomainName(domain) + " (" + responseTime + "ms)");
                } else {
                    warn("⚠ " + getDomainName(domain) + " (" + responseCode + ", " + responseTime + "ms)");
                }

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

        } catch (IOException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            error("✗ " + getDomainName(domain) + " (" + e.getClass().getSimpleName() + ", " + responseTime + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 静默处理其他异常
        }
    }

    /**
     * 获取简化的域名显示
     */
    private String getDomainName(String url) {
        try {
            URL parsedUrl = new URL(url);
            String host = parsedUrl.getHost();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host.length() > 20 ? host.substring(0, 17) + "..." : host;
        } catch (Exception e) {
            return url.length() > 20 ? url.substring(0, 17) + "..." : url;
        }
    }

    @Override
    public void log(String level, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        // 批处理日志
        String formattedMessage = String.format("[%s][%s] %s\n",
                TimeUtils.getCurrentDateTime(), level, message);

        logQueue.offer(formattedMessage);

        // 如果队列积压太多，立即处理
        if (logQueue.size() > LOG_BATCH_SIZE * 2) {
            flushLogs();
        }
    }

    /**
     * 刷新日志到UI
     */
    private void flushLogs() {
        if (isLogProcessing || !isLogAreaAvailable() || logQueue.isEmpty()) {
            return;
        }

        isLogProcessing = true;
        try {
            StringBuilder batch = new StringBuilder();
            int count = 0;

            // 批量获取日志
            while (count < LOG_BATCH_SIZE && !logQueue.isEmpty()) {
                String log = logQueue.poll();
                if (log != null) {
                    batch.append(log);
                    count++;
                }
            }

            if (batch.length() > 0) {
                final String logsToAppend = batch.toString();
                Platform.runLater(() -> {
                    try {
                        appendToLogArea(logsToAppend);
                    } catch (Exception e) {
                        // 静默处理UI异常
                    }
                });
            }
        } finally {
            isLogProcessing = false;
        }
    }

    /**
     * 追加日志到UI区域
     */
    private void appendToLogArea(String message) {
        if (!isLogAreaAvailable()) {
            return;
        }

        try {
            // 简化日志管理
            String currentText = logArea.getText();
            if (currentText.length() > 10000) { // 限制总文本长度
                int cutIndex = currentText.indexOf('\n', 2000);
                if (cutIndex > 0) {
                    logArea.deleteText(0, cutIndex + 1);
                }
            }

            logArea.appendText(message);

            // 降低滚动频率
            if (Math.random() < 0.3) { // 30%的概率滚动
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        } catch (Exception e) {
            // 静默处理
        }
    }

    /**
     * 检查日志区域是否可用
     */
    private boolean isLogAreaAvailable() {
        return logArea != null && logArea.getScene() != null;
    }

    @Override
    public TextArea getLogArea() {
        return logArea;
    }

    // 便利方法 - 减少日志输出
    @Override
    public void info(String message) {
        log("INFO", message);
    }
    @Override
    public void error(String message) {
        log("ERROR", message);
    }

    public void warn(String message) {
        log("WARN", message);
    }

    @Override
    public void debug(String message) {
        // 调试日志只在需要时输出
        if (logQueue.size() < 5) { // 队列不拥挤时才记录调试日志
            log("DEBUG", message);
        }
    }

    /**
     * 获取当前活跃的域名数量
     */
    public int getActiveDomainCount() {
        return schedulers.size();
    }

    /**
     * 检查域名是否正在保活
     */
    public boolean isDomainActive(String domain) {
        return schedulers.containsKey(domain);
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
        new Thread(() -> {
            Thread.currentThread().setName("TestPing-" + getDomainName(domain));
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            pingDomain(domain);
        }).start();
    }

    /**
     * 获取服务状态信息
     */
    public String getServiceStatus() {
        int activeCount = schedulers.size();
        int configCount = configs.size();
        int enabledCount = (int) configs.values().stream()
                .filter(KeepAliveConfig::isEnabled)
                .count();

        return String.format("配置: %d, 启用: %d, 运行中: %d",
                configCount, enabledCount, activeCount);
    }
}