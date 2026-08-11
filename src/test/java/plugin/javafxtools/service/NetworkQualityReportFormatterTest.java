package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.service.NetworkQualityService.ConnectionInfo;
import plugin.javafxtools.service.NetworkQualityService.MonitorSpec;
import plugin.javafxtools.service.NetworkQualityService.ProbeAccumulator;
import plugin.javafxtools.service.NetworkQualityService.ProbeResult;
import plugin.javafxtools.service.NetworkQualityService.Protocol;
import plugin.javafxtools.service.NetworkQualityService.ProxySettings;
import plugin.javafxtools.service.NetworkQualityService.ProxyType;
import plugin.javafxtools.service.NetworkQualityService.Route;
import plugin.javafxtools.service.NetworkQualityService.RoutePlan;
import plugin.javafxtools.service.NetworkQualityService.SessionSnapshot;
import plugin.javafxtools.service.NetworkQualityService.Target;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkQualityReportFormatterTest {

    @Test
    void reportContainsActionableMetricsWithoutProxyCredentials() {
        Target target = new Target("service", "Local API", Protocol.HTTP,
                "service.internal", 8080, "/health", true);
        ProbeAccumulator accumulator = new ProbeAccumulator(
                new MonitorSpec(target, Route.PROXY, true, ""));
        ConnectionInfo connection = new ConnectionInfo("127.0.0.1", 50_000,
                "Loopback", "HTTP CONNECT 127.0.0.1:10808");
        accumulator.accept(ProbeResult.success(40, null, connection, "HTTP 200 OK"));
        accumulator.accept(ProbeResult.success(60, null, connection, "HTTP 200 OK"));
        accumulator.accept(ProbeResult.failure(
                "服务返回 HTTP 503 Service Unavailable",
                "HTTP 503 Service Unavailable", connection));
        SessionSnapshot snapshot = new SessionSnapshot(false, Instant.now(),
                Duration.ofSeconds(65), Duration.ofSeconds(2), Duration.ofSeconds(3),
                RoutePlan.PROXY_ONLY,
                List.of(accumulator.snapshot()));
        ProxySettings secret = new ProxySettings(ProxyType.HTTP_CONNECT,
                "127.0.0.1", 10808, "support-user", "secret-value");

        String report = NetworkQualityReportFormatter.format(snapshot);

        assertTrue(report.contains("Local API"));
        assertTrue(report.contains("P95"));
        assertTrue(report.contains("抖动"));
        assertTrue(report.contains("连续失败 1"));
        assertTrue(report.contains("间隔 2 秒 | 超时 3 秒"));
        assertTrue(report.contains("最近采样:"));
        assertTrue(report.contains(secret.authority()));
        assertFalse(report.contains(secret.username()));
        assertFalse(report.contains(secret.password()));
    }

    @Test
    void reportDoesNotClaimPerfectAvailabilityBeforeAnyProbe() {
        Target target = new Target("waiting", "Waiting API", Protocol.HTTPS,
                "example.com", 443, "/health", true);
        ProbeAccumulator accumulator = new ProbeAccumulator(
                new MonitorSpec(target, Route.SYSTEM, true, ""));
        SessionSnapshot snapshot = new SessionSnapshot(true, Instant.now(),
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(500),
                RoutePlan.SYSTEM_ONLY, List.of(accumulator.snapshot()));

        String report = NetworkQualityReportFormatter.format(snapshot);

        assertTrue(report.contains("当前 -- | 平均 -- | P95 -- | 峰值 -- | 抖动 -- | 可用 --"));
    }

    @Test
    void reportDoesNotShowZeroLatencyWhenRecentWindowHasNoSuccess() {
        Target target = new Target("outage", "Outage API", Protocol.HTTP,
                "service.internal", 8080, "/health", true);
        ProbeAccumulator accumulator = new ProbeAccumulator(
                new MonitorSpec(target, Route.SYSTEM, true, ""));
        ConnectionInfo connection = new ConnectionInfo("192.0.2.10", 50_000,
                "Test Adapter", "系统路由");
        accumulator.accept(ProbeResult.success(25, null, connection, "HTTP 200 OK"));
        for (int index = 0; index < NetworkQualityService.RECENT_QUALITY_SAMPLES; index++) {
            accumulator.accept(ProbeResult.failure("响应超时"));
        }
        SessionSnapshot snapshot = new SessionSnapshot(true, Instant.now(),
                Duration.ofMinutes(2), Duration.ofSeconds(2), Duration.ofSeconds(3),
                RoutePlan.SYSTEM_ONLY, List.of(accumulator.snapshot()));

        String report = NetworkQualityReportFormatter.format(snapshot);

        assertTrue(report.contains("当前 -- | 平均 -- | P95 -- | 峰值 -- | 抖动 -- | 可用 0.0%"));
        assertFalse(report.contains("平均 0.0 ms"));
    }
}
