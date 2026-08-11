package plugin.javafxtools.model;

import java.nio.file.Path;
import java.time.Instant;

public record LogMonitorMatch(String ruleId, String ruleName, String expression,
                              Path logFile, String line, Instant matchedAt) {
}
