package plugin.javafxtools.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import plugin.javafxtools.model.LogMatchMode;
import plugin.javafxtools.model.LogMonitorAutomation;
import plugin.javafxtools.model.LogMonitorConfig;
import plugin.javafxtools.model.LogMonitorRule;
import plugin.javafxtools.model.LogRemoteMatchAction;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;
import plugin.javafxtools.util.HttpUrlSupport;

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
    private static final Set<String> ROOT_FIELDS = Set.of(
            "enabled", "logFile", "rules", "automation");
    private static final Set<String> LEGACY_ROOT_FIELDS = Set.of("enabled", "logFile", "rules");
    private static final Set<String> RULE_FIELDS = Set.of(
            "id", "name", "expression", "mode", "caseSensitive", "enabled");
    private static final Set<String> AUTOMATION_FIELDS = Set.of(
            "enabled", "triggerRuleIds", "targetWindow", "typeText", "text", "pressEnter",
            "startAtMatch", "everyMatches", "maxExecutions", "remoteCheckEnabled",
            "remoteUrl", "remoteKeyword", "remoteMatchAction");
    private static final Set<String> LEGACY_AUTOMATION_FIELDS = Set.of(
            "enabled", "triggerRuleId", "targetWindow", "typeText", "text", "pressEnter",
            "startAtMatch", "everyMatches", "maxExecutions", "remoteCheckEnabled",
            "remoteUrl", "remoteKeyword", "remoteMatchAction");

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
        if (root == null || !root.isObject()
                || !(fieldNamesMatch(root, ROOT_FIELDS)
                || fieldNamesMatch(root, LEGACY_ROOT_FIELDS))
                || !root.path("enabled").isBoolean()
                || !isNonBlankText(root.path("logFile"))
                || !root.path("rules").isArray()) {
            throw invalid("root object");
        }

        List<LogMonitorRule> rules = new ArrayList<>();
        for (JsonNode rule : root.path("rules")) {
            rules.add(parseRule(rule));
        }
        LogMonitorAutomation automation = root.has("automation")
                ? parseAutomation(root.get("automation"))
                : LogMonitorAutomation.defaults(rules);
        LogMonitorConfig config = new LogMonitorConfig(root.get("enabled").booleanValue(),
                root.get("logFile").textValue(), rules, automation);
        validateConfig(config);
        return config;
    }

    private static LogMonitorAutomation parseAutomation(JsonNode automation) throws IOException {
        boolean modernFields = automation != null && automation.isObject()
                && fieldNamesMatch(automation, AUTOMATION_FIELDS);
        boolean legacyFields = automation != null && automation.isObject()
                && fieldNamesMatch(automation, LEGACY_AUTOMATION_FIELDS);
        if (automation == null || !automation.isObject()
                || !(modernFields || legacyFields)
                || !automation.path("enabled").isBoolean()
                || (modernFields
                ? !automation.path("triggerRuleIds").isArray()
                : !automation.path("triggerRuleId").isTextual())
                || !automation.path("targetWindow").isTextual()
                || !automation.path("typeText").isBoolean()
                || !automation.path("text").isTextual()
                || !automation.path("pressEnter").isBoolean()
                || !isInt(automation.path("startAtMatch"))
                || !isInt(automation.path("everyMatches"))
                || !isInt(automation.path("maxExecutions"))
                || !automation.path("remoteCheckEnabled").isBoolean()
                || !automation.path("remoteUrl").isTextual()
                || !automation.path("remoteKeyword").isTextual()
                || !isNonBlankText(automation.path("remoteMatchAction"))) {
            throw invalid("automation");
        }
        try {
            List<String> triggerRuleIds = new ArrayList<>();
            if (modernFields) {
                for (JsonNode triggerRuleId : automation.path("triggerRuleIds")) {
                    if (!isNonBlankText(triggerRuleId)) {
                        throw invalid("automation trigger rules");
                    }
                    triggerRuleIds.add(triggerRuleId.textValue());
                }
            } else {
                triggerRuleIds.add(automation.get("triggerRuleId").textValue());
            }
            return new LogMonitorAutomation(
                    automation.get("enabled").booleanValue(),
                    triggerRuleIds,
                    automation.get("targetWindow").textValue(),
                    automation.get("typeText").booleanValue(),
                    automation.get("text").textValue(),
                    automation.get("pressEnter").booleanValue(),
                    automation.get("startAtMatch").intValue(),
                    automation.get("everyMatches").intValue(),
                    automation.get("maxExecutions").intValue(),
                    automation.get("remoteCheckEnabled").booleanValue(),
                    automation.get("remoteUrl").textValue(),
                    automation.get("remoteKeyword").textValue(),
                    LogRemoteMatchAction.valueOf(
                            automation.get("remoteMatchAction").textValue()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid log monitor automation", exception);
        }
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
            config.automation().validate(config.rules());
            if (config.automation().enabled() && config.automation().remoteCheckEnabled()) {
                HttpUrlSupport.toUrl(config.automation().remoteUrl());
            }
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

    private static boolean isInt(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IOException invalid(String subject) {
        return new IOException("Invalid log monitor " + subject);
    }
}
