package plugin.javafxtools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * IP 地址范围识别和按需公网归属信息查询。
 */
public final class IpInformationService {
    private static final String API_ENDPOINT = "https://ipwho.is/";
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_FIELD_LENGTH = 256;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public IpInformation lookupTarget(String input, int timeoutMillis) throws IOException {
        validateTimeout(timeoutMillis);
        NetworkDiagnosticService.Target target =
                NetworkDiagnosticService.parseTarget(input, "");
        List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(target.host()));
        if (addresses.isEmpty()) {
            throw new IOException("目标没有可查询的 IP 地址");
        }
        InetAddress selected = addresses.stream()
                .filter(IpInformationService::isPublicAddress)
                .findFirst()
                .orElse(addresses.getFirst());
        if (!isPublicAddress(selected)) {
            return localInformation(target.original(), selected);
        }
        return request(target.original(), selected.getHostAddress(), timeoutMillis);
    }

    public IpInformation lookupPublicAddress(int timeoutMillis) throws IOException {
        validateTimeout(timeoutMillis);
        return request("当前公网出口", "", timeoutMillis);
    }

    static IpInformation parseResponse(String query, String json) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        if (root == null || !root.path("success").asBoolean(true)) {
            String message = text(root, "message");
            throw new IOException(message.isBlank() ? "IP 信息服务返回失败" : message);
        }

        JsonNode connection = root.path("connection");
        JsonNode timezone = root.path("timezone");
        String asnValue = connection.path("asn").isNumber()
                ? "AS" + connection.path("asn").asLong()
                : normalizeAsn(text(connection, "asn"));
        return new IpInformation(
                limit(query),
                text(root, "ip"),
                text(root, "type"),
                "公网 / 全局地址",
                false,
                text(root, "continent"),
                text(root, "country"),
                text(root, "region"),
                text(root, "city"),
                text(root, "postal"),
                text(connection, "isp"),
                text(connection, "org"),
                asnValue,
                text(connection, "domain"),
                text(timezone, "id"),
                text(timezone, "utc"),
                coordinate(root.path("latitude")),
                coordinate(root.path("longitude")),
                "ipwho.is",
                "公网归属信息仅供网络诊断参考");
    }

    private IpInformation request(String query, String address, int timeoutMillis)
            throws IOException {
        String suffix = address == null || address.isBlank()
                ? "" : URLEncoder.encode(address, StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) URI.create(API_ENDPOINT + suffix)
                .toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "FxTools/1.0 IP-Diagnostics");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("IP 信息服务响应异常: HTTP " + status);
            }
            return parseResponse(query, readBounded(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        try (InputStream stream = input) {
            byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("IP 信息服务响应过大");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static IpInformation localInformation(String query, InetAddress address) {
        String family = address.getAddress().length == 4 ? "IPv4" : "IPv6";
        return new IpInformation(limit(query), address.getHostAddress(), family,
                NetworkDiagnosticService.addressScope(address), true,
                "", "", "", "", "", "", "", "", "", "", "", "", "",
                "本地分析", "私有、本地和回环地址没有公网归属信息");
    }

    private static boolean isPublicAddress(InetAddress address) {
        return "公网 / 全局地址".equals(NetworkDiagnosticService.addressScope(address));
    }

    private static void validateTimeout(int timeoutMillis) {
        if (timeoutMillis < 250 || timeoutMillis > 30_000) {
            throw new IllegalArgumentException("超时时间必须在 250 到 30000 毫秒之间");
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : limit(value.asText());
    }

    private static String coordinate(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return "";
        }
        return limit(value.asText());
    }

    private static String normalizeAsn(String value) {
        if (value.isBlank() || value.regionMatches(true, 0, "AS", 0, 2)) {
            return value;
        }
        return "AS" + value;
    }

    private static String limit(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= MAX_FIELD_LENGTH
                ? text : text.substring(0, MAX_FIELD_LENGTH);
    }

    public record IpInformation(String query, String ip, String type, String scope,
                                boolean local, String continent, String country,
                                String region, String city, String postal,
                                String isp, String organization, String asn,
                                String domain, String timezone, String utcOffset,
                                String latitude, String longitude,
                                String dataSource, String note) {
        public String location() {
            return join(country, region, city, postal);
        }

        public String network() {
            return join(isp, organization, asn, domain);
        }

        public String timeZoneDisplay() {
            return join(timezone, utcOffset);
        }

        public String coordinates() {
            return latitude.isBlank() || longitude.isBlank()
                    ? "" : latitude + ", " + longitude;
        }

        private static String join(String... values) {
            return Arrays.stream(values)
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .reduce((left, right) -> left + " / " + right)
                    .orElse("暂无数据");
        }
    }
}
