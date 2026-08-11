package plugin.javafxtools.model;

import java.nio.file.Path;
import java.util.List;

public record LogMonitorConfig(boolean enabled, String logFile, List<LogMonitorRule> rules) {

    public static LogMonitorConfig defaults() {
        String logFile = Path.of(System.getProperty("user.home"), "Desktop", "cc-switch.log").toString();
        return new LogMonitorConfig(true, logFile, List.of(
                new LogMonitorRule("429", "HTTP 429", "429", LogMatchMode.WHOLE_TOKEN, true, true),
                new LogMonitorRule("503", "HTTP 503", "503", LogMatchMode.WHOLE_TOKEN, true, true)
        ));
    }
}
