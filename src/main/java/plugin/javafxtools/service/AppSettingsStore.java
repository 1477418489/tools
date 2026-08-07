package plugin.javafxtools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import plugin.javafxtools.model.AppSettings;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 应用设置的当前格式 JSON 存储。
 */
public final class AppSettingsStore {
    private static final Path DEFAULT_SETTINGS_FILE = AppDataPaths.dataFile("settings.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path settingsFile;

    public AppSettingsStore() {
        this(DEFAULT_SETTINGS_FILE);
    }

    AppSettingsStore(Path settingsFile) {
        this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile");
    }

    public AppSettings load() throws IOException {
        if (!Files.exists(settingsFile)) {
            return AppSettings.defaults();
        }
        JsonNode root = MAPPER.readTree(settingsFile.toFile());
        validate(root);
        return MAPPER.treeToValue(root, AppSettings.class);
    }

    public void save(AppSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        AtomicFileWriter.writeUtf8(settingsFile,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
    }

    private void validate(JsonNode root) throws IOException {
        if (root == null || !root.isObject()
                || root.size() != 3
                || !root.path("closeToTray").isBoolean()
                || !root.path("reminderSoundEnabled").isBoolean()
                || !root.path("startWithWindows").isBoolean()) {
            throw new IOException("应用设置格式无效");
        }
    }
}
