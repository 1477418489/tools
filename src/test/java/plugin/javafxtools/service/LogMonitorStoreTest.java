package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.model.LogMatchMode;
import plugin.javafxtools.model.LogMonitorConfig;
import plugin.javafxtools.model.LogMonitorRule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
