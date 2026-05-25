package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.KeepAliveConfig;
import plugin.javafxtools.model.KeepAliveMethod;
import plugin.javafxtools.util.TimeUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/**
 * 增强版域名保活服务，支持多域名独立配置、随机间隔和 HTTP/Ping 两种保活方式。
 */
public class EnhancedKeepAliveService implements ModuleLogger {
    /**
     * 所有域名共享的保活调度器，避免域名数量增加时线性创建线程。
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "KeepAlive-Worker");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
    );

    /**
     * 每个域名当前排队的下一次任务。
     */
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    /**
     * 当前处于运行状态的域名集合。
     */
    private final Set<String> activeDomains = ConcurrentHashMap.newKeySet();

    /**
     * 当前已加载的保活配置。
     */
    private final Map<String, KeepAliveConfig> configs = new ConcurrentHashMap<>();

    /**
     * 保活页签日志输出区域。
     */
    private TextArea logArea;

    /**
     * HTTP 连接和 Ping 等待的基础超时时间。
     */
    private static final int CONNECT_TIMEOUT = 8000;

    /**
     * HTTP 读取超时时间。
     */
    private static final int READ_TIMEOUT = 10000;

    /**
     * 等待批量刷新的日志队列。
     */
    private final Queue<String> logQueue = new ConcurrentLinkedQueue<>();

    /**
     * 定时刷新日志队列的执行器。
     */
    private ScheduledExecutorService logExecutor;

    /**
     * 日志刷新中的并发保护标记。
     */
    private volatile boolean isLogProcessing = false;

    /**
     * 单次最多刷新的日志数量。
     */
    private static final int LOG_BATCH_SIZE = 10;

    /**
     * 日志队列最大积压数量。
     */
    private static final int MAX_LOG_QUEUE_SIZE = 200;

    /**
     * 日志刷新间隔，单位毫秒。
     */
    private static final long LOG_FLUSH_INTERVAL = 100;

    /**
     * 创建域名保活服务。
     *
     * @param logArea 日志输出区域
     */
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
        logExecutor.scheduleWithFixedDelay(this::flushLogs,
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
        Set<String> domainsToStop = new HashSet<>(activeDomains);

        // 更新配置
        configs.clear();
        for (KeepAliveConfig config : configList) {
            if (config != null) {
                configs.put(config.getDomain(), config);
                if (config.isEnabled()) {
                    domainsToStop.remove(config.getDomain());
                }
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
        activeDomains.add(domain);

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

        // 执行保活
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
        if (activeDomains.remove(domain)) {
            info("停止: " + getDomainName(domain));
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
     * 清理资源 - 停止所有域名任务并释放线程池
     */
    public void cleanup() {
        info("清理保活服务资源...");

        // 取消所有计划任务
        for (ScheduledFuture<?> future : scheduledFutures.values()) {
            if (future != null) {
                future.cancel(true);
            }
        }
        scheduledFutures.clear();

        activeDomains.clear();

        // 关闭共享保活调度器
        scheduler.shutdownNow();

        // 关闭日志处理器
        if (logExecutor != null) {
            logExecutor.shutdownNow();
        }

        // 清空数据
        configs.clear();
        logQueue.clear();
        logArea = null;
    }

    /**
     * 访问域名以保持其活跃状态
     */
    private void pingDomain(String domain) {
        KeepAliveConfig config = configs.get(domain);
        if (config == null || !config.isEnabled()) {
            return;
        }

        if (config.getMethod() == KeepAliveMethod.PING) {
            pingDomainByWindowsCommand(domain);
            return;
        }

        requestDomainByHttp(domain);
    }

    /**
     * 通过 HTTP GET 请求访问域名。
     *
     * @param domain 域名或 URL
     */
    private void requestDomainByHttp(String domain) {
        long startTime = System.currentTimeMillis();
        try {
            // 随机用户代理
            String[] userAgents = {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Edge/120.0.0.0 Safari/537.36",
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
     * 通过 Windows ping.exe 命令访问域名主机。
     *
     * @param domain 域名或 URL
     */
    private void pingDomainByWindowsCommand(String domain) {
        long startTime = System.currentTimeMillis();
        Process process = null;
        try {
            String host = getPingHost(domain);
            ProcessBuilder builder = new ProcessBuilder(
                    "ping.exe",
                    "-n", "1",
                    "-w", String.valueOf(CONNECT_TIMEOUT),
                    host
            );
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            process = builder.start();
            boolean finished = process.waitFor(CONNECT_TIMEOUT + 2000L, TimeUnit.MILLISECONDS);
            long responseTime = System.currentTimeMillis() - startTime;
            if (!finished) {
                process.destroyForcibly();
                error("✗ Ping " + getDomainName(domain) + " (超时, " + responseTime + "ms)");
                return;
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                info("✓ Ping " + getDomainName(domain) + " (" + responseTime + "ms)");
            } else {
                warn("⚠ Ping " + getDomainName(domain) + " (退出码 " + exitCode + ", " + responseTime + "ms)");
            }
        } catch (IOException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            error("✗ Ping " + getDomainName(domain) + " (" + e.getClass().getSimpleName() + ", " + responseTime + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 从 URL 中解析 Ping 命令可使用的主机名。
     *
     * @param domain 域名或 URL
     * @return 主机名
     * @throws IOException URL 无法解析主机名时抛出
     */
    private String getPingHost(String domain) throws IOException {
        String host = new URL(domain).getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("无法解析Ping主机名");
        }
        return host;
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

    /**
     * 记录保活模块日志。
     *
     * @param level 日志级别
     * @param message 日志内容
     */
    @Override
    public void log(String level, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        // 批处理日志
        String formattedMessage = String.format("[%s][%s] %s\n",
                TimeUtils.getCurrentDateTime(), level, message);

        logQueue.offer(formattedMessage);
        trimLogQueue();

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

    /**
     * 获取日志输出区域。
     *
     * @return 日志输出区域
     */
    @Override
    public TextArea getLogArea() {
        return logArea;
    }

    /**
     * 限制日志队列积压，避免界面不可用时日志无限占用内存。
     */
    private void trimLogQueue() {
        while (logQueue.size() > MAX_LOG_QUEUE_SIZE) {
            logQueue.poll();
        }
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
        // 调试日志只在需要时输出
        if (logQueue.size() < 5) { // 队列不拥挤时才记录调试日志
            log("DEBUG", message);
        }
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
        scheduler.execute(() -> pingDomain(domain));
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
