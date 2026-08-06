package plugin.javafxtools.service;

import plugin.javafxtools.model.KeepAliveConfig;
import plugin.javafxtools.model.KeepAliveMethod;
import plugin.javafxtools.util.HttpUrlSupport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Consumer;

/**
 * 域名保活的 HTTP 和 Ping 探测执行器。
 *
 * @author wwj
 */
public class KeepAliveProbeService {
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 10000;
    private static final String USER_AGENT = "JavaFxTools-KeepAlive/1.0";

    private final Consumer<String> infoLogger;
    private final Consumer<String> warnLogger;
    private final Consumer<String> errorLogger;

    /**
     * 创建探测执行器。
     *
     * @param infoLogger 信息日志回调
     * @param warnLogger 警告日志回调
     * @param errorLogger 错误日志回调
     */
    public KeepAliveProbeService(Consumer<String> infoLogger,
                                 Consumer<String> warnLogger,
                                 Consumer<String> errorLogger) {
        this.infoLogger = infoLogger;
        this.warnLogger = warnLogger;
        this.errorLogger = errorLogger;
    }

    /**
     * 按配置执行一次保活探测。
     *
     * @param config 保活配置
     */
    public void pingDomain(KeepAliveConfig config) {
        if (config == null || !config.isEnabled()) {
            return;
        }

        String domain = config.getDomain();
        if (config.getMethod() == KeepAliveMethod.PING) {
            pingDomainByAddress(domain);
            return;
        }

        requestDomainByHttp(domain);
    }

    /**
     * 获取简化的域名显示。
     *
     * @param url 域名或 URL
     * @return 简化后的域名
     */
    public String getDomainName(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URL parsedUrl = parseUrl(url);
            String host = parsedUrl.getHost();
            if (host == null || host.isBlank()) {
                return abbreviate(url);
            }
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return abbreviate(host);
        } catch (Exception e) {
            return abbreviate(url);
        }
    }

    private void requestDomainByHttp(String domain) {
        long startTime = System.currentTimeMillis();
        try {
            HttpURLConnection connection = null;
            try {
                URL url = parseUrl(domain);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Cache-Control", "no-cache");

                int responseCode = connection.getResponseCode();
                long responseTime = System.currentTimeMillis() - startTime;
                if (responseCode >= 200 && responseCode < 300) {
                    infoLogger.accept("✓ " + getDomainName(domain) + " (" + responseTime + "ms)");
                } else {
                    warnLogger.accept("⚠ " + getDomainName(domain) + " (" + responseCode + ", " + responseTime + "ms)");
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        } catch (IOException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            errorLogger.accept("✗ " + getDomainName(domain)
                    + " (" + e.getClass().getSimpleName() + ", " + responseTime + "ms)");
        } catch (RuntimeException e) {
            errorLogger.accept("探测失败: " + getDomainName(domain)
                    + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void pingDomainByAddress(String domain) {
        long startTime = System.currentTimeMillis();
        try {
            String host = getPingHost(domain);
            boolean reachable = InetAddress.getByName(host).isReachable(CONNECT_TIMEOUT);
            long responseTime = System.currentTimeMillis() - startTime;
            if (reachable) {
                infoLogger.accept("✓ Ping " + getDomainName(domain) + " (" + responseTime + "ms)");
            } else {
                warnLogger.accept("⚠ Ping " + getDomainName(domain) + " (不可达, " + responseTime + "ms)");
            }
        } catch (IOException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            errorLogger.accept("✗ Ping " + getDomainName(domain)
                    + " (" + e.getClass().getSimpleName() + ", " + responseTime + "ms)");
        }
    }

    private String getPingHost(String domain) throws IOException {
        String host = parseUrl(domain).getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("无法解析Ping主机名");
        }
        return host;
    }

    private URL parseUrl(String value) throws IOException {
        return HttpUrlSupport.toUrl(value);
    }

    private String abbreviate(String value) {
        return value.length() > 20 ? value.substring(0, 17) + "..." : value;
    }
}
