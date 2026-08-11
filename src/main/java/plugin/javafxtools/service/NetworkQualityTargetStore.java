package plugin.javafxtools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import plugin.javafxtools.service.NetworkQualityService.Protocol;
import plugin.javafxtools.service.NetworkQualityService.Target;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Strict current-baseline storage for network quality targets. */
public final class NetworkQualityTargetStore {
    private static final Path DEFAULT_FILE = AppDataPaths.dataFile("network_quality_targets.json");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path file;

    public NetworkQualityTargetStore() {
        this(DEFAULT_FILE);
    }

    NetworkQualityTargetStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public List<Target> load() throws IOException {
        if (!Files.exists(file)) {
            return defaultTargets();
        }
        try {
            return parse(mapper.readTree(file.toFile()));
        } catch (Exception e) {
            throw new IOException("网络质量目标配置无效", e);
        }
    }

    public void save(List<Target> targets) throws IOException {
        validate(targets);
        backupInvalidExisting();
        ArrayNode root = mapper.createArrayNode();
        for (Target target : targets) {
            ObjectNode node = root.addObject();
            node.put("id", target.id());
            node.put("name", target.name());
            node.put("protocol", target.protocol().name());
            node.put("host", target.host());
            node.put("port", target.port());
            node.put("requestTarget", target.requestTarget());
            node.put("enabled", target.enabled());
        }
        AtomicFileWriter.writeUtf8(file, mapper.writeValueAsString(root));
    }

    public static List<Target> defaultTargets() {
        return List.of(
                new Target("http-ms-connect", "Microsoft 连通性", Protocol.HTTP,
                        "www.msftconnecttest.com", 80, "/connecttest.txt", true),
                new Target("https-cloudflare", "Cloudflare HTTPS", Protocol.HTTPS,
                        "www.cloudflare.com", 443, "/cdn-cgi/trace", true),
                new Target("tcp-cloudflare", "Cloudflare TCP", Protocol.TCP,
                        "www.cloudflare.com", 443, false),
                new Target("tls-microsoft", "Microsoft TLS", Protocol.TLS,
                        "www.microsoft.com", 443, false),
                new Target("stun-cloudflare", "Cloudflare STUN", Protocol.STUN_UDP,
                        "stun.cloudflare.com", 3478, false));
    }

    private List<Target> parse(JsonNode root) throws IOException {
        if (root == null || !root.isArray()) {
            throw new IOException("配置根节点必须是数组");
        }
        List<Target> targets = new ArrayList<>();
        for (JsonNode node : root) {
            boolean currentSchema = node.isObject() && node.size() == 7
                    && text(node, "requestTarget");
            boolean legacySchema = node.isObject() && node.size() == 6
                    && !node.has("requestTarget");
            if ((!currentSchema && !legacySchema)
                    || !text(node, "id") || !text(node, "name")
                    || !text(node, "protocol") || !text(node, "host")
                    || !node.hasNonNull("port") || !node.get("port").isIntegralNumber()
                    || !node.hasNonNull("enabled") || !node.get("enabled").isBoolean()) {
                throw new IOException("网络质量目标字段不符合当前格式");
            }
            try {
                targets.add(new Target(node.get("id").textValue(),
                        node.get("name").textValue(),
                        Protocol.valueOf(node.get("protocol").textValue()),
                        node.get("host").textValue(), node.get("port").intValue(),
                        currentSchema ? node.get("requestTarget").textValue() : "/",
                        node.get("enabled").booleanValue()));
            } catch (IllegalArgumentException e) {
                throw new IOException("网络质量目标字段无效", e);
            }
        }
        validate(targets);
        return List.copyOf(targets);
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

    private static void validate(List<Target> targets) throws IOException {
        if (targets == null || targets.isEmpty()
                || targets.size() > NetworkQualityService.MAX_TARGETS) {
            throw new IOException("监控目标数量必须为 1-" + NetworkQualityService.MAX_TARGETS);
        }
        Set<String> ids = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        for (Target target : targets) {
            String key = target == null ? "" : target.protocol().name() + ":"
                    + target.host().toLowerCase(Locale.ROOT) + ":" + target.port()
                    + ":" + target.requestTarget();
            if (target == null || !ids.add(target.id()) || !addresses.add(key)) {
                throw new IOException("目标 ID 或协议地址重复");
            }
        }
    }

    private static boolean text(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isTextual();
    }
}
