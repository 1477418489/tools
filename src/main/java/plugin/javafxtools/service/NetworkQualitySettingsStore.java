package plugin.javafxtools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import plugin.javafxtools.service.NetworkQualityService.ProxyType;
import plugin.javafxtools.service.NetworkQualityService.RoutePlan;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Stores non-secret network monitor settings. Proxy passwords are intentionally excluded. */
public final class NetworkQualitySettingsStore {
    private static final Path DEFAULT_FILE = AppDataPaths.dataFile("network_quality_settings.json");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path file;

    public NetworkQualitySettingsStore() {
        this(DEFAULT_FILE);
    }

    NetworkQualitySettingsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public Settings load() throws IOException {
        if (!Files.exists(file)) {
            return Settings.defaults();
        }
        try {
            return parse(mapper.readTree(file.toFile()));
        } catch (Exception e) {
            throw new IOException("网络质量出口设置无效", e);
        }
    }

    public void save(Settings settings) throws IOException {
        validate(settings);
        backupInvalidExisting();
        ObjectNode root = mapper.createObjectNode();
        root.put("routePlan", settings.routePlan().name());
        root.put("proxyType", settings.proxyType().name());
        root.put("proxyHost", settings.proxyHost());
        root.put("proxyPort", settings.proxyPort());
        root.put("proxyUsername", settings.proxyUsername());
        root.put("intervalMillis", settings.intervalMillis());
        root.put("timeoutMillis", settings.timeoutMillis());
        AtomicFileWriter.writeUtf8(file, mapper.writeValueAsString(root));
    }

    private Settings parse(JsonNode root) throws IOException {
        if (root == null || !root.isObject() || root.size() != 7
                || !text(root, "routePlan") || !text(root, "proxyType")
                || !text(root, "proxyHost") || !text(root, "proxyUsername")
                || !integer(root, "proxyPort") || !integer(root, "intervalMillis")
                || !integer(root, "timeoutMillis") || root.has("password")) {
            throw new IOException("网络质量出口设置字段不符合当前格式");
        }
        try {
            Settings settings = new Settings(
                    RoutePlan.valueOf(root.get("routePlan").textValue()),
                    ProxyType.valueOf(root.get("proxyType").textValue()),
                    root.get("proxyHost").textValue(), root.get("proxyPort").intValue(),
                    root.get("proxyUsername").textValue(),
                    root.get("intervalMillis").intValue(),
                    root.get("timeoutMillis").intValue());
            validate(settings);
            return settings;
        } catch (IllegalArgumentException e) {
            throw new IOException("网络质量出口设置字段无效", e);
        }
    }

    private void backupInvalidExisting() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try {
            parse(mapper.readTree(file.toFile()));
        } catch (Exception e) {
            Path backup = file.resolveSibling(file.getFileName() + ".invalid.bak");
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validate(Settings settings) throws IOException {
        if (settings == null || settings.routePlan() == null || settings.proxyType() == null
                || settings.proxyHost() == null || settings.proxyUsername() == null
                || settings.proxyHost().length() > 253
                || settings.proxyHost().chars().anyMatch(Character::isWhitespace)
                || settings.proxyUsername().length() > 128
                || settings.proxyPort() < 1 || settings.proxyPort() > 65_535
                || settings.intervalMillis() < NetworkQualityService.MIN_INTERVAL_MILLIS
                || settings.intervalMillis() > NetworkQualityService.MAX_INTERVAL_MILLIS
                || settings.timeoutMillis() < NetworkQualityService.MIN_TIMEOUT_MILLIS
                || settings.timeoutMillis() > NetworkQualityService.MAX_TIMEOUT_MILLIS) {
            throw new IOException("网络质量出口设置超出允许范围");
        }
        if (settings.routePlan().usesProxy() && settings.proxyHost().isBlank()) {
            throw new IOException("代理出口模式需要代理地址");
        }
    }

    private static boolean text(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isTextual();
    }

    private static boolean integer(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isIntegralNumber();
    }

    public record Settings(RoutePlan routePlan, ProxyType proxyType,
                           String proxyHost, int proxyPort, String proxyUsername,
                           int intervalMillis, int timeoutMillis) {
        public static Settings defaults() {
            return new Settings(RoutePlan.SYSTEM_ONLY, ProxyType.SOCKS5,
                    "", 1080, "", 2_000, 3_000);
        }
    }
}
