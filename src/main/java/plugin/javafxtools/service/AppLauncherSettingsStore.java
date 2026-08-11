package plugin.javafxtools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Stores batch-launch behavior independently from the ordered application list. */
public final class AppLauncherSettingsStore {
    public static final int DEFAULT_LAUNCH_DELAY_MILLIS = 1_000;
    public static final int MIN_LAUNCH_DELAY_MILLIS = 0;
    public static final int MAX_LAUNCH_DELAY_MILLIS = 60_000;

    private static final Path DEFAULT_FILE = AppDataPaths.dataFile("app_launcher_settings.json");

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path file;

    public AppLauncherSettingsStore() {
        this(DEFAULT_FILE);
    }

    AppLauncherSettingsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public Settings load() throws IOException {
        if (!Files.exists(file)) {
            return Settings.defaults();
        }
        try {
            JsonNode root = mapper.readTree(file.toFile());
            if (root == null || !root.isObject() || root.size() != 1
                    || !root.hasNonNull("launchDelayMillis")
                    || !root.get("launchDelayMillis").isIntegralNumber()) {
                throw new IOException("启动项设置字段不符合当前格式");
            }
            Settings settings = new Settings(root.get("launchDelayMillis").intValue());
            validate(settings);
            return settings;
        } catch (Exception e) {
            throw new IOException("启动项设置无效", e);
        }
    }

    public void save(Settings settings) throws IOException {
        validate(settings);
        ObjectNode root = mapper.createObjectNode();
        root.put("launchDelayMillis", settings.launchDelayMillis());
        AtomicFileWriter.writeUtf8(file, mapper.writeValueAsString(root));
    }

    private static void validate(Settings settings) throws IOException {
        if (settings == null
                || settings.launchDelayMillis() < MIN_LAUNCH_DELAY_MILLIS
                || settings.launchDelayMillis() > MAX_LAUNCH_DELAY_MILLIS) {
            throw new IOException("启动间隔必须为 0-60 秒");
        }
    }

    public record Settings(int launchDelayMillis) {
        public static Settings defaults() {
            return new Settings(DEFAULT_LAUNCH_DELAY_MILLIS);
        }
    }
}
