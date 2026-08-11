package plugin.javafxtools.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import plugin.javafxtools.model.LogMatchMode;
import plugin.javafxtools.model.LogMonitorConfig;
import plugin.javafxtools.model.LogMonitorRule;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * JSON persistence for log monitor configuration.
 */
public final class LogMonitorStore {
    private static final Path DEFAULT_CONFIG_FILE = AppDataPaths.dataFile("log-monitor.json");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Set<String> ROOT_FIELDS = Set.of("enabled", "logFile", "rules");
    private static final Set<String> RULE_FIELDS = Set.of(
            "id", "name", "expression", "mode", "caseSensitive", "enabled");

    private final Path configFile;

    public LogMonitorStore() {
        this(DEFAULT_CONFIG_FILE);
    }

    LogMonitorStore(Path configFile) {
        this.configFile = Objects.requireNonNull(configFile, "configFile");
    }

    public LogMonitorConfig load() throws IOException {
        try (InputStream inputStream = Files.newInputStream(configFile)) {
            return parseConfig(MAPPER.readTree(inputStream));
        } catch (NoSuchFileException exception) {
            return LogMonitorConfig.defaults();
        }
    }

    public void save(LogMonitorConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        validateConfig(config);
        AtomicFileWriter.writeUtf8(configFile,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config));
    }

    private static LogMonitorConfig parseConfig(JsonNode root) throws IOException {
        if (root == null || !root.isObject() || !fieldNamesMatch(root, ROOT_FIELDS)
                || !root.path("enabled").isBoolean()
                || !isNonBlankText(root.path("logFile"))
                || !root.path("rules").isArray()) {
            throw invalid("root object");
        }

        List<LogMonitorRule> rules = new ArrayList<>();
        for (JsonNode rule : root.path("rules")) {
            rules.add(parseRule(rule));
        }
        LogMonitorConfig config = new LogMonitorConfig(root.get("enabled").booleanValue(),
                root.get("logFile").textValue(), rules);
        validateConfig(config);
        return config;
    }

    private static LogMonitorRule parseRule(JsonNode rule) throws IOException {
        if (!rule.isObject() || !fieldNamesMatch(rule, RULE_FIELDS)
                || !isNonBlankText(rule.path("id"))
                || !isNonBlankText(rule.path("name"))
                || !isNonBlankText(rule.path("expression"))
                || !isNonBlankText(rule.path("mode"))
                || !rule.path("caseSensitive").isBoolean()
                || !rule.path("enabled").isBoolean()) {
            throw invalid("rule");
        }
        LogMatchMode mode;
        try {
            mode = LogMatchMode.valueOf(rule.get("mode").textValue());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid log monitor rule mode", exception);
        }
        return new LogMonitorRule(rule.get("id").textValue(), rule.get("name").textValue(),
                rule.get("expression").textValue(), mode,
                rule.get("caseSensitive").booleanValue(), rule.get("enabled").booleanValue());
    }

    private static void validateConfig(LogMonitorConfig config) throws IOException {
        if (config.logFile() == null || config.logFile().isBlank()) {
            throw invalid("configuration");
        }
        try {
            for (LogMonitorRule rule : config.rules()) {
                if (rule == null || isBlank(rule.id()) || isBlank(rule.name()) || isBlank(rule.expression())
                        || rule.mode() == null) {
                    throw invalid("rule");
                }
            }
            new LogMonitorMatcher(config.rules());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid log monitor configuration", exception);
        }
    }

    private static boolean fieldNamesMatch(JsonNode node, Set<String> expected) {
        if (node.size() != expected.size()) {
            return false;
        }
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return expected.containsAll(names);
    }

    private static boolean isNonBlankText(JsonNode node) {
        return node.isTextual() && !node.textValue().isBlank();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IOException invalid(String subject) {
        return new IOException("Invalid log monitor " + subject);
    }
}
