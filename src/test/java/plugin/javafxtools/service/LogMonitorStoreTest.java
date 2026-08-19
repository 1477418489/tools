package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.model.LogMatchMode;
import plugin.javafxtools.model.LogMonitorAutomation;
import plugin.javafxtools.model.LogMonitorConfig;
import plugin.javafxtools.model.LogMonitorRule;
import plugin.javafxtools.model.LogRemoteMatchAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogMonitorStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void missingFileUsesCurrentDefaults() throws Exception {
        LogMonitorStore store = new LogMonitorStore(tempDirectory.resolve("log-monitor.json"));

        assertEquals(LogMonitorConfig.defaults(), store.load());
    }

    @Test
    void configurationRoundTripsUsingUtf8() throws Exception {
        LogMonitorConfig config = new LogMonitorConfig(true, "D:/logs/\u6d4b\u8bd5.log", List.of(
                new LogMonitorRule("\u89c4\u5219-1", "\u9519\u8bef \u68c0\u6d4b", "\u5f02\u5e38", LogMatchMode.CONTAINS, false, true)));
        LogMonitorStore store = new LogMonitorStore(tempDirectory.resolve("log-monitor.json"));

        store.save(config);

        assertTrue(new String(Files.readAllBytes(tempDirectory.resolve("log-monitor.json")), StandardCharsets.UTF_8)
                .contains("\u89c4\u5219-1"));
        assertTrue(Files.readString(tempDirectory.resolve("log-monitor.json"), StandardCharsets.UTF_8)
                .contains("D:/logs/\u6d4b\u8bd5.log"));
        assertEquals(config, store.load());
    }

    @Test
    void automationConfigurationRoundTrips() throws Exception {
        LogMonitorRule rule = new LogMonitorRule("429", "HTTP 429", "429",
                LogMatchMode.WHOLE_TOKEN, true, true);
        LogMonitorAutomation automation = new LogMonitorAutomation(
                true, rule.id(), "Codex", true, "继续执行", true,
                2, 3, 4, true, "https://chybenzun.top", "继续",
                LogRemoteMatchAction.NO_ACTION);
        LogMonitorConfig config = new LogMonitorConfig(false, "D:/logs/codex.log",
                List.of(rule), automation);
        LogMonitorStore store = new LogMonitorStore(tempDirectory.resolve("log-monitor.json"));

        store.save(config);

        String savedJson = Files.readString(tempDirectory.resolve("log-monitor.json"),
                StandardCharsets.UTF_8);
        assertTrue(savedJson.contains("\"triggerRuleIds\""));
        assertFalse(savedJson.contains("\"triggerRuleId\":"));
        assertEquals(config, store.load());
    }

    @Test
    void automationConfigurationRoundTripsMultipleTriggerRules() throws Exception {
        List<LogMonitorRule> rules = List.of(
                new LogMonitorRule("429", "HTTP 429", "429",
                        LogMatchMode.WHOLE_TOKEN, true, true),
                new LogMonitorRule("503", "HTTP 503", "503",
                        LogMatchMode.WHOLE_TOKEN, true, true));
        LogMonitorAutomation automation = new LogMonitorAutomation(
                true, List.of("429", "503"), "Codex", true, "继续执行", true,
                1, 1, 4, false, "https://example.com", "allow",
                LogRemoteMatchAction.CONTINUE_INPUT);
        LogMonitorConfig config = new LogMonitorConfig(false, "D:/logs/codex.log",
                rules, automation);
        LogMonitorStore store = new LogMonitorStore(tempDirectory.resolve("log-monitor.json"));

        store.save(config);

        assertEquals(config, store.load());
    }

    @Test
    void legacyConfigurationLoadsWithAutomationDisabled() throws Exception {
        Path file = write(configJson(ruleJson()));

        LogMonitorConfig loaded = new LogMonitorStore(file).load();

        assertFalse(loaded.automation().enabled());
        assertEquals("id", loaded.automation().triggerRuleId());
    }

    @Test
    void legacyConfigurationWithLongRuleIdStillMigrates() throws Exception {
        String longId = "x".repeat(300);
        Path file = write(configJson(ruleJson().replace("\"id\":\"id\"",
                "\"id\":\"" + longId + "\"")));

        LogMonitorConfig loaded = new LogMonitorStore(file).load();

        assertFalse(loaded.automation().enabled());
        assertEquals(longId, loaded.automation().triggerRuleId());
    }

    @Test
    void enabledAutomationMustReferenceAnEnabledRuleAndValidHttpUrl() throws Exception {
        String valid = automationConfigJson("id", "https://example.com", "CONTINUE_INPUT");
        write(valid);
        assertEquals("id", new LogMonitorStore(tempDirectory.resolve("log-monitor.json"))
                .load().automation().triggerRuleId());

        assertRejected(automationConfigJson("missing", "https://example.com", "CONTINUE_INPUT"));
        assertRejected(automationConfigJson("id", "file:///tmp/check", "CONTINUE_INPUT"));
        assertRejected(automationConfigJson("id", "https://example.com", "UNKNOWN"));
        assertRejected(automationConfigJson("id", "https://example.com", "CONTINUE_INPUT")
                .replace("\"startAtMatch\":1", "\"startAtMatch\":1.5"));
    }

    @Test
    void disabledAutomationDoesNotValidateDormantRemoteUrl() throws Exception {
        String json = automationConfigJson("id", "not a url", "CONTINUE_INPUT")
                .replace("\"automation\":{\"enabled\":true",
                        "\"automation\":{\"enabled\":false");

        LogMonitorConfig loaded = new LogMonitorStore(write(json)).load();

        assertFalse(loaded.automation().enabled());
    }

    @Test
    void disabledLegacyAutomationMayHaveNoTriggerRule() throws Exception {
        String json = automationConfigJson("", "not a url", "CONTINUE_INPUT")
                .replace("\"automation\":{\"enabled\":true",
                        "\"automation\":{\"enabled\":false");

        LogMonitorAutomation loaded = new LogMonitorStore(write(json)).load().automation();

        assertFalse(loaded.enabled());
        assertTrue(loaded.triggerRuleIds().isEmpty());
    }

    @Test
    void malformedFileIsRejectedWithoutChangingIt() throws Exception {
        Path file = write("{not json");
        byte[] original = Files.readAllBytes(file);

        assertThrows(IOException.class, () -> new LogMonitorStore(file).load());

        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void rootSchemaMustContainOnlyExpectedFieldsWithExpectedTypes() throws Exception {
        assertRejected("{}");
        assertRejected("{\"enabled\":true,\"logFile\":\"a.log\",\"rules\":[],\"extra\":1}");
        assertRejected("{\"enabled\":\"true\",\"logFile\":\"a.log\",\"rules\":[]}");
        assertRejected("{\"enabled\":true,\"logFile\":\"   \",\"rules\":[]}");
        assertRejected("{\"enabled\":true,\"logFile\":\"a.log\",\"rules\":{}}");
    }

    @Test
    void duplicateRootPropertiesAreRejectedWithoutChangingTheSource() throws Exception {
        assertRejected("{\"enabled\":true,\"enabled\":false,\"logFile\":\"a.log\",\"rules\":[]}");
    }

    @Test
    void ruleSchemaMustContainOnlyExpectedFieldsWithExpectedTypes() throws Exception {
        assertRejected(configJson("{}"));
        assertRejected(configJson(ruleJson().replace("}", ",\"extra\":true}")));
        assertRejected(configJson("{\"id\":1,\"name\":\"name\",\"expression\":\"error\",\"mode\":\"CONTAINS\",\"caseSensitive\":true,\"enabled\":true}"));
        assertRejected(configJson("{\"id\":\"id\",\"name\":\"name\",\"expression\":\"error\",\"mode\":\"CONTAINS\",\"caseSensitive\":\"true\",\"enabled\":true}"));
        assertRejected(configJson("{\"id\":\"id\",\"name\":\"name\",\"expression\":\"error\",\"mode\":\"UNKNOWN\",\"caseSensitive\":true,\"enabled\":true}"));
        assertRejected(configJson("{\"id\":\"  \",\"name\":\"name\",\"expression\":\"error\",\"mode\":\"CONTAINS\",\"caseSensitive\":true,\"enabled\":true}"));
        assertRejected(configJson("{\"id\":\"id\",\"name\":\"  \",\"expression\":\"error\",\"mode\":\"CONTAINS\",\"caseSensitive\":true,\"enabled\":true}"));
    }

    @Test
    void duplicateRulePropertiesAreRejectedWithoutChangingTheSource() throws Exception {
        assertRejected(configJson("{\"id\":\"id\",\"id\":\"other\",\"name\":\"name\",\"expression\":\"error\",\"mode\":\"CONTAINS\",\"caseSensitive\":true,\"enabled\":true}"));
    }

    @Test
    void blankExpressionsAndDuplicateDefinitionsAreRejected() throws Exception {
        assertRejected(configJson("{\"id\":\"id\",\"name\":\"name\",\"expression\":\"   \",\"mode\":\"CONTAINS\",\"caseSensitive\":true,\"enabled\":true}"));
        assertRejected("{\"enabled\":true,\"logFile\":\"a.log\",\"rules\":[" + ruleJson() + "," + ruleJson() + "]}");
    }

    @Test
    void invalidOrNullConfigCannotBeSavedAndExistingFileRemainsUnchanged() throws Exception {
        Path file = tempDirectory.resolve("log-monitor.json");
        LogMonitorStore store = new LogMonitorStore(file);
        store.save(new LogMonitorConfig(true, "a.log", List.of(
                new LogMonitorRule("id", "name", "error", LogMatchMode.CONTAINS, true, true))));
        byte[] original = Files.readAllBytes(file);
        LogMonitorConfig invalid = new LogMonitorConfig(true, "a.log", List.of(
                new LogMonitorRule("id", "name", "[", LogMatchMode.REGEX, true, true)));

        assertThrows(IOException.class, () -> store.save(invalid));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertThrows(NullPointerException.class, () -> store.save(null));

        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void directoryTargetDoesNotUseDefaults() throws Exception {
        Path directory = tempDirectory.resolve("log-monitor.json");
        Files.createDirectory(directory);

        assertThrows(IOException.class, () -> new LogMonitorStore(directory).load());
    }

    @Test
    void loadedRuleListsAreDefensive() throws Exception {
        LogMonitorStore store = new LogMonitorStore(tempDirectory.resolve("log-monitor.json"));
        List<LogMonitorRule> suppliedRules = new ArrayList<>(List.of(
                new LogMonitorRule("id", "name", "error", LogMatchMode.CONTAINS, true, true)));
        LogMonitorConfig config = new LogMonitorConfig(true, "a.log", suppliedRules);
        suppliedRules.clear();

        store.save(config);
        LogMonitorConfig loaded = store.load();

        assertEquals(1, loaded.rules().size());
        assertThrows(UnsupportedOperationException.class, () -> loaded.rules().add(loaded.rules().getFirst()));
    }

    private void assertRejected(String json) throws Exception {
        Path file = write(json);
        byte[] original = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> new LogMonitorStore(file).load());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    private Path write(String content) throws IOException {
        Path file = tempDirectory.resolve("log-monitor.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String configJson(String rule) {
        return "{\"enabled\":true,\"logFile\":\"a.log\",\"rules\":[" + rule + "]}";
    }

    private static String ruleJson() {
        return "{\"id\":\"id\",\"name\":\"name\",\"expression\":\"error\",\"mode\":\"CONTAINS\",\"caseSensitive\":true,\"enabled\":true}";
    }

    private static String automationConfigJson(String ruleId, String url, String action) {
        return "{\"enabled\":true,\"logFile\":\"a.log\",\"rules\":[" + ruleJson()
                + "],\"automation\":{"
                + "\"enabled\":true,\"triggerRuleId\":\"" + ruleId
                + "\",\"targetWindow\":\"Codex\",\"typeText\":true,"
                + "\"text\":\"continue\",\"pressEnter\":true,"
                + "\"startAtMatch\":1,\"everyMatches\":1,\"maxExecutions\":1,"
                + "\"remoteCheckEnabled\":true,\"remoteUrl\":\"" + url
                + "\",\"remoteKeyword\":\"allow\",\"remoteMatchAction\":\""
                + action + "\"}}";
    }
}
