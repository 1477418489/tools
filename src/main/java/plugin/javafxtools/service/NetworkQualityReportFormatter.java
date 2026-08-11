package plugin.javafxtools.service;

import plugin.javafxtools.service.NetworkQualityService.MonitorSnapshot;
import plugin.javafxtools.service.NetworkQualityService.ProbeSample;
import plugin.javafxtools.service.NetworkQualityService.Quality;
import plugin.javafxtools.service.NetworkQualityService.SessionSnapshot;
import plugin.javafxtools.service.NetworkQualityService.TargetEndpoint;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Builds a credential-free plain-text report for support and troubleshooting. */
public final class NetworkQualityReportFormatter {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final List<Quality> QUALITY_ORDER = List.of(
            Quality.WAITING, Quality.EXCELLENT, Quality.GOOD,
            Quality.DEGRADED, Quality.POOR, Quality.OFFLINE);

    private NetworkQualityReportFormatter() {
    }

    public static String format(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<MonitorSnapshot> supported = snapshot.monitors().stream()
                .filter(MonitorSnapshot::supported).toList();
        long sent = supported.stream().mapToLong(MonitorSnapshot::sent).sum();
        long recentSamples = 0;
        long recentSuccesses = 0;
        for (MonitorSnapshot monitor : supported) {
            List<ProbeSample> history = monitor.history();
            int start = Math.max(0,
                    history.size() - NetworkQualityService.RECENT_QUALITY_SAMPLES);
            List<ProbeSample> recent = history.subList(start, history.size());
            recentSamples += recent.size();
            recentSuccesses += recent.stream().filter(ProbeSample::success).count();
        }

        StringBuilder report = new StringBuilder(1_024);
        report.append("FxTools 网络质量诊断报告\n")
                .append("生成时间: ").append(TIME_FORMAT.format(java.time.Instant.now())).append('\n')
                .append("会话状态: ").append(snapshot.running() ? "运行中" : "已停止")
                .append(" | 时长 ").append(formatDuration(snapshot.elapsed()))
                .append(" | 间隔 ").append(formatProbeDuration(snapshot.interval()))
                .append(" | 超时 ").append(formatProbeDuration(snapshot.timeout()))
                .append(" | 出口 ").append(snapshot.routePlan().displayName()).append('\n')
                .append("总体质量: ").append(overallQuality(supported).displayName())
                .append(" | 有效线路 ").append(supported.size())
                .append(" | 探测 ").append(sent)
                .append(" | 近期可用率 ")
                .append(recentSamples == 0 ? "--"
                        : formatPercent(recentSuccesses * 100.0 / recentSamples))
                .append('\n');

        Map<String, List<MonitorSnapshot>> byTarget = new LinkedHashMap<>();
        for (MonitorSnapshot monitor : snapshot.monitors()) {
            byTarget.computeIfAbsent(monitor.target().id(),
                    ignored -> new java.util.ArrayList<>()).add(monitor);
        }
        for (List<MonitorSnapshot> targetMonitors : byTarget.values()) {
            MonitorSnapshot first = targetMonitors.getFirst();
            TargetEndpoint endpoint = new TargetEndpoint(first.target().protocol(),
                    first.target().host(), first.target().port(),
                    first.target().requestTarget());
            report.append("\n[").append(first.target().name()).append("] ")
                    .append(first.target().protocol().displayName())
                    .append(" · ").append(endpoint.displayValue()).append('\n');
            for (MonitorSnapshot monitor : targetMonitors) {
                appendMonitor(report, monitor);
            }
        }
        return report.toString().stripTrailing();
    }

    private static void appendMonitor(StringBuilder report, MonitorSnapshot monitor) {
        report.append("  ").append(monitor.route().displayName()).append(": ");
        if (!monitor.supported()) {
            report.append("不支持");
            appendIfPresent(report, " | ", monitor.lastError());
            report.append('\n');
            return;
        }
        report.append(monitor.quality().displayName())
                .append(" | 当前 ").append(formatMillis(monitor.lastRttMillis()))
                .append(" | 平均 ").append(formatMillis(monitor.averageRttMillis()))
                .append(" | P95 ").append(formatMillis(monitor.p95RttMillis()))
                .append(" | 峰值 ").append(formatMillis(monitor.peakRttMillis()))
                .append(" | 抖动 ").append(formatMillis(monitor.jitterMillis()))
                .append(" | 可用 ").append(monitor.sent() == 0 ? "--"
                        : formatPercent(100 - monitor.failurePercent()))
                .append(" | 总计成功/失败 ").append(monitor.received())
                .append('/').append(monitor.failed());
        if (monitor.consecutiveFailures() > 0) {
            report.append(" | 连续失败 ").append(monitor.consecutiveFailures());
        }
        report.append('\n');
        if (monitor.connection() != null) {
            report.append("    出口: ").append(monitor.connection().displayValue()).append('\n');
        }
        if (monitor.mapping() != null) {
            report.append("    公网映射: ").append(monitor.mapping().displayValue())
                    .append(" | 变化 ").append(monitor.mappingChanges()).append(" 次\n");
        }
        if (!monitor.history().isEmpty()) {
            report.append("    最近采样: ")
                    .append(TIME_FORMAT.format(monitor.history().getLast().capturedAt()))
                    .append('\n');
        }
        appendLine(report, "    最近响应: ", monitor.lastResponse());
        appendLine(report, "    最近错误: ", monitor.lastError());
    }

    private static void appendLine(StringBuilder report, String prefix, String value) {
        if (value != null && !value.isBlank()) {
            report.append(prefix).append(value.strip()).append('\n');
        }
    }

    private static void appendIfPresent(StringBuilder report, String prefix, String value) {
        if (value != null && !value.isBlank()) {
            report.append(prefix).append(value.strip());
        }
    }

    private static Quality overallQuality(List<MonitorSnapshot> snapshots) {
        return snapshots.stream().map(MonitorSnapshot::quality)
                .filter(quality -> quality != Quality.UNSUPPORTED)
                .max(java.util.Comparator.comparingInt(QUALITY_ORDER::indexOf))
                .orElse(Quality.WAITING);
    }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        return "%02d:%02d:%02d".formatted(
                seconds / 3_600, seconds % 3_600 / 60, seconds % 60);
    }

    private static String formatMillis(double value) {
        return Double.isNaN(value) || Double.isInfinite(value)
                ? "--" : formatDecimal(value) + " ms";
    }

    private static String formatProbeDuration(Duration duration) {
        long millis = Math.max(0, duration.toMillis());
        if (millis < 1_000) {
            return millis + " ms";
        }
        return millis % 1_000 == 0
                ? millis / 1_000 + " 秒"
                : String.format(Locale.ROOT, "%.1f 秒", millis / 1_000.0);
    }

    private static String formatPercent(double value) {
        return formatDecimal(value) + "%";
    }

    private static String formatDecimal(double value) {
        return value >= 100 ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
