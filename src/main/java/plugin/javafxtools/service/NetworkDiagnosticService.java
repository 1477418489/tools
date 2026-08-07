package plugin.javafxtools.service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 域名、IP 和 TCP 端口的单次诊断服务。
 */
public final class NetworkDiagnosticService {
    private static final int MAX_TARGET_LENGTH = 2_048;
    private static final int MIN_TIMEOUT_MILLIS = 250;
    private static final int MAX_TIMEOUT_MILLIS = 30_000;

    public DiagnosticResult inspect(String input, String requestedPort,
                                    int timeoutMillis, DiagnosticMode mode)
            throws IOException {
        if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("超时时间必须在 250 到 30000 毫秒之间");
        }
        DiagnosticMode requestedMode = mode == null ? DiagnosticMode.FULL : mode;
        Target target = parseTarget(input, requestedPort);
        if (requestedMode == DiagnosticMode.PORT_ONLY && target.port() == null) {
            throw new IllegalArgumentException("端口检查需要填写端口，或输入包含端口的 URL");
        }

        long totalStarted = System.nanoTime();
        long dnsStarted = System.nanoTime();
        InetAddress[] resolved = InetAddress.getAllByName(target.host());
        long dnsDuration = elapsedMillis(dnsStarted);
        List<InetAddress> addresses = deduplicate(resolved);
        if (addresses.isEmpty()) {
            throw new UnknownHostException(target.host());
        }

        List<AddressDetail> addressDetails = addresses.stream()
                .map(NetworkDiagnosticService::describeAddress)
                .toList();

        ReachabilityResult reachability = ReachabilityResult.notChecked();
        PortCheckResult portCheck = PortCheckResult.notChecked(target.port());
        if (requestedMode == DiagnosticMode.FULL && target.port() == null) {
            reachability = checkReachability(addresses.getFirst(), timeoutMillis);
        } else if (requestedMode != DiagnosticMode.RESOLVE_ONLY && target.port() != null) {
            portCheck = checkPort(addresses, target.port(), timeoutMillis);
            if (requestedMode == DiagnosticMode.FULL) {
                reachability = ReachabilityResult.fromPortCheck(portCheck);
            }
        }

        return new DiagnosticResult(target, addressDetails, dnsDuration,
                reachability, portCheck, elapsedMillis(totalStarted));
    }

    static Target parseTarget(String input, String requestedPort) {
        String original = input == null ? "" : input.trim();
        if (original.isEmpty()) {
            throw new IllegalArgumentException("请输入域名、IP 地址或 URL");
        }
        if (original.length() > MAX_TARGET_LENGTH) {
            throw new IllegalArgumentException("检测目标过长");
        }

        String scheme = "";
        String authority;
        if (original.contains("://")) {
            URI uri;
            try {
                uri = URI.create(original);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("URL 格式无效", e);
            }
            scheme = uri.getScheme() == null
                    ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                throw new IllegalArgumentException("URL 中缺少有效主机");
            }
        } else {
            authority = stripPathAndQuery(original);
        }

        HostAndPort hostAndPort = parseAuthority(authority);
        String host = normalizeHost(hostAndPort.host());
        Integer explicitPort = parseOptionalPort(requestedPort, "端口");
        Integer inferredPort = hostAndPort.port() != null
                ? hostAndPort.port() : defaultPortForScheme(scheme);
        Integer port = explicitPort != null ? explicitPort : inferredPort;
        String kind = addressKind(host);
        String source = scheme.isBlank()
                ? kind : kind + " · " + scheme.toUpperCase(Locale.ROOT);
        return new Target(original, host, port, source);
    }

    static String addressScope(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return "任意本地地址";
        }
        if (address.isLoopbackAddress()) {
            return "回环地址";
        }
        if (address.isLinkLocalAddress()) {
            return "链路本地";
        }
        if (address.isSiteLocalAddress() || isUniqueLocalIpv6(address)) {
            return "私有网络";
        }
        if (address.isMulticastAddress()) {
            return "组播地址";
        }
        String specialScope = specialAddressScope(address);
        if (specialScope != null) {
            return specialScope;
        }
        return "公网 / 全局地址";
    }

    private static String stripPathAndQuery(String input) {
        int end = input.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int index = input.indexOf(delimiter);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        String authority = input.substring(0, end).trim();
        if (authority.isEmpty()) {
            throw new IllegalArgumentException("检测目标中缺少有效主机");
        }
        return authority;
    }

    private static HostAndPort parseAuthority(String rawAuthority) {
        String authority = rawAuthority.trim();
        int userInfoEnd = authority.lastIndexOf('@');
        if (userInfoEnd >= 0) {
            authority = authority.substring(userInfoEnd + 1);
        }
        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            if (closingBracket < 0) {
                throw new IllegalArgumentException("IPv6 地址缺少右方括号");
            }
            String host = authority.substring(1, closingBracket);
            String remainder = authority.substring(closingBracket + 1);
            Integer port = null;
            if (!remainder.isEmpty()) {
                if (!remainder.startsWith(":")) {
                    throw new IllegalArgumentException("IPv6 地址后的端口格式无效");
                }
                port = parseOptionalPort(remainder.substring(1), "目标端口");
            }
            return new HostAndPort(host, port);
        }

        int firstColon = authority.indexOf(':');
        int lastColon = authority.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            String portText = authority.substring(lastColon + 1);
            if (portText.isBlank()) {
                throw new IllegalArgumentException("目标端口不能为空");
            }
            return new HostAndPort(authority.substring(0, lastColon),
                    parseOptionalPort(portText, "目标端口"));
        }
        return new HostAndPort(authority, null);
    }

    private static String normalizeHost(String rawHost) {
        String host = rawHost == null ? "" : rawHost.trim();
        while (host.endsWith(".") && host.length() > 1) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("主机名不能为空且不能包含空格");
        }
        if (host.contains(":")) {
            try {
                InetAddress address = InetAddress.getByName(host);
                if (!(address instanceof Inet6Address)) {
                    throw new IllegalArgumentException("IPv6 地址格式无效");
                }
                return host.toLowerCase(Locale.ROOT);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("IPv6 地址格式无效", e);
            }
        }
        try {
            String asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
            if (asciiHost.isEmpty() || asciiHost.length() > 253) {
                throw new IllegalArgumentException("域名或 IPv4 地址长度无效");
            }
            return asciiHost;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("域名或 IPv4 地址格式无效", e);
        }
    }

    private static Integer parseOptionalPort(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            int port = Integer.parseInt(text.trim());
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(fieldName + "必须在 1 到 65535 之间");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + "必须是 1 到 65535 的整数", e);
        }
    }

    private static Integer defaultPortForScheme(String scheme) {
        return switch (scheme) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            case "ftp" -> 21;
            case "ssh" -> 22;
            default -> null;
        };
    }

    private static String addressKind(String host) {
        if (host.contains(":")) {
            return "IPv6";
        }
        if (host.matches("[0-9.]+")) {
            return "IPv4";
        }
        return "域名";
    }

    private static List<InetAddress> deduplicate(InetAddress[] addresses) {
        Map<String, InetAddress> unique = new LinkedHashMap<>();
        for (InetAddress address : addresses) {
            unique.putIfAbsent(address.getHostAddress(), address);
        }
        return new ArrayList<>(unique.values());
    }

    private static AddressDetail describeAddress(InetAddress address) {
        String family = address instanceof Inet4Address ? "IPv4" : "IPv6";
        return new AddressDetail(address.getHostAddress(), family, addressScope(address));
    }

    private static ReachabilityResult checkReachability(InetAddress address, int timeoutMillis)
            throws InterruptedIOException {
        checkInterrupted();
        long started = System.nanoTime();
        try {
            boolean reachable = address.isReachable(timeoutMillis);
            String detail = reachable
                    ? "主机响应系统可达性探测"
                    : "未收到响应，防火墙可能已拦截探测";
            return new ReachabilityResult(true, reachable,
                    elapsedMillis(started), detail);
        } catch (IOException e) {
            return new ReachabilityResult(true, false,
                    elapsedMillis(started), friendlyFailure(e));
        }
    }

    private static PortCheckResult checkPort(List<InetAddress> addresses, int port,
                                             int timeoutMillis)
            throws InterruptedIOException {
        long started = System.nanoTime();
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        IOException lastFailure = null;
        String lastAddress = "";
        for (InetAddress address : addresses) {
            checkInterrupted();
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                lastFailure = new SocketTimeoutException("连接超时");
                break;
            }
            int remainingMillis = (int) Math.max(1,
                    Math.min(Integer.MAX_VALUE, remainingNanos / 1_000_000L));
            lastAddress = address.getHostAddress();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), remainingMillis);
                return new PortCheckResult(true, true, port, lastAddress,
                        elapsedMillis(started), "TCP 连接建立成功");
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        String detail = lastFailure == null
                ? "没有可检测的地址" : friendlyFailure(lastFailure);
        return new PortCheckResult(true, false, port, lastAddress,
                elapsedMillis(started), detail);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("检测已取消");
        }
    }

    private static String friendlyFailure(IOException exception) {
        if (exception instanceof SocketTimeoutException) {
            return "连接超时";
        }
        if (exception instanceof ConnectException) {
            return "连接被拒绝或目标不可达";
        }
        if (exception instanceof NoRouteToHostException) {
            return "没有到目标主机的可用路由";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte first = address.getAddress()[0];
        return (first & 0xfe) == 0xfc;
    }

    private static String specialAddressScope(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            if (first == 100 && second >= 64 && second <= 127) {
                return "运营商级 NAT";
            }
            if ((first == 192 && second == 0 && third == 2)
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)) {
                return "文档保留地址";
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return "网络基准测试地址";
            }
            if (first == 0 || first >= 240) {
                return "保留地址";
            }
        }
        if (address instanceof Inet6Address && bytes.length == 16
                && (bytes[0] & 0xff) == 0x20
                && (bytes[1] & 0xff) == 0x01
                && (bytes[2] & 0xff) == 0x0d
                && (bytes[3] & 0xff) == 0xb8) {
            return "文档保留地址";
        }
        return null;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public enum DiagnosticMode {
        FULL,
        RESOLVE_ONLY,
        PORT_ONLY
    }

    public record Target(String original, String host, Integer port, String source) {
    }

    public record AddressDetail(String address, String family, String scope) {
    }

    public record ReachabilityResult(boolean checked, boolean reachable,
                                     long durationMillis, String detail) {
        private static ReachabilityResult notChecked() {
            return new ReachabilityResult(false, false, 0, "未执行主机响应探测");
        }

        private static ReachabilityResult fromPortCheck(PortCheckResult portCheck) {
            return new ReachabilityResult(true, portCheck.open(),
                    portCheck.durationMillis(), portCheck.open()
                    ? "TCP 连接成功，目标主机可达"
                    : "TCP 连接未建立，无法确认主机可达性");
        }
    }

    public record PortCheckResult(boolean checked, boolean open, Integer port,
                                  String address, long durationMillis, String detail) {
        private static PortCheckResult notChecked(Integer port) {
            return new PortCheckResult(false, false, port, "", 0,
                    port == null ? "未指定端口" : "未执行端口检查");
        }
    }

    public record DiagnosticResult(Target target, List<AddressDetail> addresses,
                                   long dnsDurationMillis,
                                   ReachabilityResult reachability,
                                   PortCheckResult portCheck,
                                   long totalDurationMillis) {
        public DiagnosticResult {
            addresses = List.copyOf(addresses);
        }
    }

    private record HostAndPort(String host, Integer port) {
    }
}
