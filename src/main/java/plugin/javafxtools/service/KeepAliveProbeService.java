package plugin.javafxtools.service;

import plugin.javafxtools.model.KeepAliveConfig;
import plugin.javafxtools.model.KeepAliveMethod;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 域名保活的 HTTP 和 Ping 探测执行器。
 *
 * @author wwj
 */
public class KeepAliveProbeService {
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 10000;
    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Edge/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/121.0"
    };

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
            pingDomainByWindowsCommand(domain);
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

    private void requestDomainByHttp(String domain) {
        long startTime = System.currentTimeMillis();
        try {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(domain);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", randomUserAgent());
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                connection.setRequestProperty("Accept", "text/html");
                connection.setRequestProperty("Cache-Control", "no-cache");

                Thread.sleep((long) (Math.random() * 1000));

                int responseCode = connection.getResponseCode();
                long responseTime = System.currentTimeMillis() - startTime;
                if (responseCode == HttpURLConnection.HTTP_OK) {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Preserve previous behavior: unexpected probe failures should not break scheduling.
        }
    }

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
                errorLogger.accept("✗ Ping " + getDomainName(domain) + " (超时, " + responseTime + "ms)");
                return;
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                infoLogger.accept("✓ Ping " + getDomainName(domain) + " (" + responseTime + "ms)");
            } else {
                warnLogger.accept("⚠ Ping " + getDomainName(domain)
                        + " (退出码 " + exitCode + ", " + responseTime + "ms)");
            }
        } catch (IOException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            errorLogger.accept("✗ Ping " + getDomainName(domain)
                    + " (" + e.getClass().getSimpleName() + ", " + responseTime + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String getPingHost(String domain) throws IOException {
        String host = new URL(domain).getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("无法解析Ping主机名");
        }
        return host;
    }

    private String randomUserAgent() {
        return USER_AGENTS[(int) (Math.random() * USER_AGENTS.length)];
    }
}
