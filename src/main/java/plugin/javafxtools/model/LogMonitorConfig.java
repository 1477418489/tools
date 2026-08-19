package plugin.javafxtools.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record LogMonitorConfig(boolean enabled, String logFile, List<LogMonitorRule> rules,
                               LogMonitorAutomation automation) {

    public LogMonitorConfig {
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        automation = Objects.requireNonNull(automation, "automation");
    }

    public LogMonitorConfig(boolean enabled, String logFile, List<LogMonitorRule> rules) {
        this(enabled, logFile, rules, LogMonitorAutomation.defaults(rules));
    }

    public static LogMonitorConfig defaults() {
        String logFile = Path.of(System.getProperty("user.home"), "Desktop", "cc-switch.log").toString();
        List<LogMonitorRule> rules = List.of(
                new LogMonitorRule("429", "HTTP 429", "429", LogMatchMode.WHOLE_TOKEN, true, true),
                new LogMonitorRule("503", "HTTP 503", "503", LogMatchMode.WHOLE_TOKEN, true, true)
        );
        return new LogMonitorConfig(true, logFile, rules, LogMonitorAutomation.defaults(rules));
    }
}
