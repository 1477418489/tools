package plugin.javafxtools.service;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.PortUnreachableException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bounded network quality monitor for system-routed and explicitly proxied probes.
 */
public final class NetworkQualityService implements AutoCloseable {
    public static final int MAX_TARGETS = 10;
    public static final int MAX_MONITORS = MAX_TARGETS * 2;
    public static final int MAX_HISTORY_SAMPLES = 180;
    public static final int RECENT_QUALITY_SAMPLES = 60;
    public static final int MIN_INTERVAL_MILLIS = 1_000;
    public static final int MAX_INTERVAL_MILLIS = 60_000;
    public static final int MIN_TIMEOUT_MILLIS = 500;
    public static final int MAX_TIMEOUT_MILLIS = 5_000;

    private static final int MAX_WORKERS = 6;
    private static final int SNAPSHOT_COALESCE_MILLIS = 250;
    private static final int MAX_HTTP_HEADER_BYTES = 16 * 1_024;
    private static final int MAX_UDP_PACKET_BYTES = 4 * 1_024;
    private static final int STUN_HEADER_LENGTH = 20;
    private static final int STUN_BINDING_REQUEST = 0x0001;
    private static final int STUN_BINDING_SUCCESS = 0x0101;
    private static final int STUN_BINDING_ERROR = 0x0111;
    private static final int STUN_MAGIC_COOKIE = 0x2112A442;
    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_ERROR_CODE = 0x0009;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Object lifecycleLock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private volatile Session session;

    public void start(List<Target> targets, RoutePlan routePlan, ProxySettings proxy,
                      Duration interval, Duration timeout,
                      Consumer<SessionSnapshot> listener) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(routePlan, "routePlan");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(listener, "listener");
        validateTargets(targets);
        if (routePlan.usesProxy() && proxy == null) {
            throw new IllegalArgumentException("当前出口模式需要代理设置");
        }
        long intervalMillis = interval.toMillis();
        long timeoutMillis = timeout.toMillis();
        if (intervalMillis < MIN_INTERVAL_MILLIS || intervalMillis > MAX_INTERVAL_MILLIS) {
            throw new IllegalArgumentException("探测间隔超出允许范围");
        }
        if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("响应超时超出允许范围");
        }
        if (targets.stream().noneMatch(Target::enabled)) {
            throw new IllegalArgumentException("请至少启用一个监控目标");
        }

        List<MonitorSpec> specs = createMonitorSpecs(targets, routePlan, proxy);
        if (specs.size() > MAX_MONITORS) {
            throw new IllegalArgumentException("监控项数量超过上限");
        }
        if (specs.stream().noneMatch(MonitorSpec::supported)) {
            throw new IllegalArgumentException("当前代理类型不支持已启用目标的协议");
        }

        Session candidate;
        synchronized (lifecycleLock) {
            stopLocked();
            long sessionGeneration = generation.incrementAndGet();
            candidate = new Session(sessionGeneration, specs, proxy,
                    (int) intervalMillis, (int) timeoutMillis, listener);
            session = candidate;
            candidate.scheduler.scheduleAtFixedRate(
                    () -> dispatchProbes(candidate), 0, intervalMillis, TimeUnit.MILLISECONDS);
        }
        publish(candidate);
    }

    public void stop() {
        Session stopped;
        synchronized (lifecycleLock) {
            stopped = session;
            stopLocked();
        }
        if (stopped != null) {
            publishStopped(stopped);
        }
    }

    public boolean isRunning() {
        Session current = session;
        return current != null && !current.closed.get();
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            stopLocked();
        }
    }

    public static boolean supports(Protocol protocol, ProxyType proxyType) {
        return proxyType == ProxyType.SOCKS5 || protocol != Protocol.STUN_UDP;
    }

    static ProbeResult probeOnce(Target target, Route route, ProxySettings proxy,
                                 int timeoutMillis) throws IOException {
        if (target.protocol() == Protocol.STUN_UDP) {
            try (StunChannel channel = route == Route.SYSTEM
                    ? new DirectStunChannel(target)
                    : new Socks5StunChannel(target, Objects.requireNonNull(proxy), timeoutMillis)) {
                return channel.probe(timeoutMillis);
            }
        }
        return probeStream(target, route, proxy, timeoutMillis,
                target.protocol() == Protocol.TLS || target.protocol() == Protocol.HTTPS);
    }

    private void dispatchProbes(Session candidate) {
        if (!isCurrent(candidate)) {
            return;
        }
        for (MonitorState state : candidate.states) {
            if (!state.spec.supported() || !state.inFlight.compareAndSet(false, true)) {
                continue;
            }
            try {
                candidate.workers.execute(() -> runProbe(candidate, state));
            } catch (RejectedExecutionException e) {
                state.inFlight.set(false);
            }
        }
    }

    private void runProbe(Session candidate, MonitorState state) {
        try {
            if (!isCurrent(candidate)) {
                return;
            }
            ProbeResult result;
            try {
                result = state.probe(candidate.proxy, candidate.timeoutMillis);
            } catch (Exception e) {
                result = ProbeResult.failure(readableError(e));
            }
            synchronized (lifecycleLock) {
                if (!isCurrent(candidate)) {
                    return;
                }
                synchronized (state) {
                    state.statistics.accept(result);
                }
            }
            requestPublish(candidate);
        } finally {
            state.inFlight.set(false);
        }
    }

    private void publish(Session candidate) {
        if (!isCurrent(candidate)) {
            return;
        }
        try {
            candidate.listener.accept(candidate.snapshot(true));
        } catch (RuntimeException ignored) {
            // Listener failures must not terminate monitoring.
        }
    }

    private void requestPublish(Session candidate) {
        if (!isCurrent(candidate) || !candidate.snapshotScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            candidate.scheduler.schedule(() -> {
                candidate.snapshotScheduled.set(false);
                publish(candidate);
            }, SNAPSHOT_COALESCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            candidate.snapshotScheduled.set(false);
        }
    }

    private void publishStopped(Session stopped) {
        try {
            stopped.listener.accept(stopped.snapshot(false));
        } catch (RuntimeException ignored) {
            // The owner may already be shutting down.
        }
    }

    private boolean isCurrent(Session candidate) {
        return session == candidate && !candidate.closed.get()
                && generation.get() == candidate.generation;
    }

    private void stopLocked() {
        generation.incrementAndGet();
        Session current = session;
        session = null;
        if (current != null) {
            current.close();
        }
    }

    private static List<MonitorSpec> createMonitorSpecs(List<Target> targets,
                                                         RoutePlan plan,
                                                         ProxySettings proxy) {
        List<MonitorSpec> specs = new ArrayList<>();
        for (Target target : targets) {
            if (!target.enabled()) {
                continue;
            }
            if (plan.usesSystemRoute()) {
                specs.add(new MonitorSpec(target, Route.SYSTEM, true, ""));
            }
            if (plan.usesProxy()) {
                boolean supported = supports(target.protocol(), proxy.type());
                String reason = supported ? "" : "HTTP CONNECT 不支持 UDP/STUN";
                specs.add(new MonitorSpec(target, Route.PROXY, supported, reason));
            }
        }
        return List.copyOf(specs);
    }

    private static ProbeResult probeStream(Target target, Route route,
                                           ProxySettings proxy, int timeoutMillis,
                                           boolean tls) throws IOException {
        long started = System.nanoTime();
        Deadline deadline = new Deadline(timeoutMillis);
        try (Socket socket = openStreamSocket(target, route, proxy, deadline)) {
            ConnectionInfo connection = connectionInfo(socket, route, proxy, null);
            if (tls) {
                socket.setSoTimeout(deadline.remainingMillis());
                SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket sslSocket = (SSLSocket) sslFactory
                        .createSocket(socket, target.host(), target.port(), true)) {
                    SSLParameters parameters = sslSocket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    try {
                        parameters.setServerNames(List.of(new SNIHostName(target.host())));
                    } catch (IllegalArgumentException ignored) {
                        // IP literals do not use an SNI host name.
                    }
                    sslSocket.setSSLParameters(parameters);
                    sslSocket.setSoTimeout(deadline.remainingMillis());
                    sslSocket.startHandshake();
                    if (target.protocol() == Protocol.HTTPS) {
                        return probeHttp(target, sslSocket, connection, started, deadline);
                    }
                }
            }
            if (target.protocol() == Protocol.HTTP) {
                return probeHttp(target, socket, connection, started, deadline);
            }
            return ProbeResult.success(elapsedMillis(started), null, connection);
        }
    }

    private static ProbeResult probeHttp(Target target, Socket socket,
                                         ConnectionInfo connection, long started,
                                         Deadline deadline) throws IOException {
        String authority = target.host().contains(":")
                ? "[" + target.host() + "]:" + target.port()
                : target.host() + ":" + target.port();
        String request = "GET " + target.requestTarget() + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + "User-Agent: FxTools-NetworkQuality/1.0\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n\r\n";
        socket.setSoTimeout(deadline.remainingMillis());
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();

        byte[] header = readHttpHeader(socket, socket.getInputStream(), deadline);
        String firstLine = new String(header, StandardCharsets.ISO_8859_1)
                .split("\\r?\\n", 2)[0].strip();
        String[] parts = firstLine.split("\\s+", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("HTTP 目标响应无效");
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("HTTP 目标状态码无效", e);
        }
        String response = parts.length == 3
                ? "HTTP " + status + " " + sanitizeError(parts[2]) : "HTTP " + status;
        if (status < 100 || status > 599) {
            throw new IOException("HTTP 目标状态码超出范围");
        }
        if (status < 200 || status >= 400) {
            return ProbeResult.failure("服务返回 " + response, response, connection);
        }
        return ProbeResult.success(elapsedMillis(started), null, connection, response);
    }

    private static Socket openStreamSocket(Target target, Route route,
                                           ProxySettings proxy, Deadline deadline)
            throws IOException {
        if (route == Route.SYSTEM) {
            return connectSocket(target.host(), target.port(), deadline);
        }
        Objects.requireNonNull(proxy, "proxy");
        Socket socket = connectSocket(proxy.host(), proxy.port(), deadline);
        boolean success = false;
        try {
            if (proxy.type() == ProxyType.SOCKS5) {
                socks5Command(socket, proxy, 0x01, target.host(), target.port(), deadline);
            } else {
                httpConnect(socket, proxy, target.host(), target.port(), deadline);
            }
            success = true;
            return socket;
        } finally {
            if (!success) {
                socket.close();
            }
        }
    }

    private static Socket connectSocket(String host, int port, Deadline deadline)
            throws IOException {
        Socket socket = new Socket();
        boolean success = false;
        try {
            socket.connect(new InetSocketAddress(host, port), deadline.remainingMillis());
            socket.setSoTimeout(deadline.remainingMillis());
            success = true;
            return socket;
        } finally {
            if (!success) {
                socket.close();
            }
        }
    }

    private static InetSocketAddress socks5Command(Socket socket, ProxySettings proxy,
                                                    int command, String host, int port,
                                                    Deadline deadline) throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        byte[] username = proxy.username().getBytes(StandardCharsets.UTF_8);
        byte[] password = proxy.password().getBytes(StandardCharsets.UTF_8);
        if (username.length > 255 || password.length > 255) {
            throw new IOException("SOCKS5 用户名或密码过长");
        }
        if (username.length == 0) {
            output.write(new byte[]{0x05, 0x01, 0x00});
        } else {
            output.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        }
        output.flush();
        byte[] greeting = readExact(socket, input, 2, deadline);
        if (greeting[0] != 0x05 || greeting[1] == (byte) 0xff) {
            throw new IOException("SOCKS5 代理没有可用认证方式");
        }
        if (greeting[1] == 0x02) {
            ByteArrayOutputStream authBytes = new ByteArrayOutputStream();
            authBytes.write(0x01);
            authBytes.write(username.length);
            authBytes.write(username);
            authBytes.write(password.length);
            authBytes.write(password);
            output.write(authBytes.toByteArray());
            output.flush();
            byte[] authReply = readExact(socket, input, 2, deadline);
            if (authReply[0] != 0x01 || authReply[1] != 0x00) {
                throw new IOException("SOCKS5 用户名或密码认证失败");
            }
        } else if (greeting[1] != 0x00) {
            throw new IOException("SOCKS5 代理返回不支持的认证方式");
        }

        ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
        DataOutputStream request = new DataOutputStream(requestBytes);
        request.writeByte(0x05);
        request.writeByte(command);
        request.writeByte(0x00);
        if (command == 0x03) {
            request.writeByte(0x01);
            request.writeInt(0);
            request.writeShort(0);
        } else {
            writeSocksAddress(request, host, port);
        }
        output.write(requestBytes.toByteArray());
        output.flush();

        byte[] replyHeader = readExact(socket, input, 4, deadline);
        if (replyHeader[0] != 0x05) {
            throw new IOException("SOCKS5 代理响应版本无效");
        }
        if (replyHeader[1] != 0x00) {
            throw new IOException("SOCKS5 代理连接失败: "
                    + socksReplyMessage(Byte.toUnsignedInt(replyHeader[1])));
        }
        InetAddress boundAddress;
        int addressType = Byte.toUnsignedInt(replyHeader[3]);
        if (addressType == 0x01) {
            boundAddress = InetAddress.getByAddress(readExact(socket, input, 4, deadline));
        } else if (addressType == 0x04) {
            boundAddress = InetAddress.getByAddress(readExact(socket, input, 16, deadline));
        } else if (addressType == 0x03) {
            int length = Byte.toUnsignedInt(readExact(socket, input, 1, deadline)[0]);
            String boundHost = new String(readExact(socket, input, length, deadline),
                    StandardCharsets.UTF_8);
            boundAddress = InetAddress.getByName(boundHost);
        } else {
            throw new IOException("SOCKS5 代理响应地址类型无效");
        }
        byte[] portBytes = readExact(socket, input, 2, deadline);
        int boundPort = (Byte.toUnsignedInt(portBytes[0]) << 8)
                | Byte.toUnsignedInt(portBytes[1]);
        if (boundAddress.isAnyLocalAddress()) {
            boundAddress = socket.getInetAddress();
        }
        return new InetSocketAddress(boundAddress, boundPort);
    }

    private static void writeSocksAddress(DataOutputStream output, String host, int port)
            throws IOException {
        byte[] literal = ipLiteral(host);
        if (literal != null) {
            output.writeByte(literal.length == 4 ? 0x01 : 0x04);
            output.write(literal);
            output.writeShort(port);
            return;
        }
        byte[] hostBytes;
        try {
            hostBytes = IDN.toASCII(host).getBytes(StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            throw new IOException("SOCKS5 目标域名无效", e);
        }
        if (hostBytes.length == 0 || hostBytes.length > 255) {
            throw new IOException("SOCKS5 目标域名长度无效");
        }
        output.writeByte(0x03);
        output.writeByte(hostBytes.length);
        output.write(hostBytes);
        output.writeShort(port);
    }

    private static byte[] ipLiteral(String host) {
        if (host.contains(":")) {
            try {
                byte[] address = InetAddress.getByName(host).getAddress();
                return address.length == 16 ? address : null;
            } catch (Exception e) {
                return null;
            }
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].isEmpty() || !parts[index].chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                int value = Integer.parseInt(parts[index]);
                if (value > 255) {
                    return null;
                }
                address[index] = (byte) value;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return address;
    }

    private static String socksReplyMessage(int code) {
        return switch (code) {
            case 1 -> "代理内部错误";
            case 2 -> "规则不允许连接";
            case 3 -> "网络不可达";
            case 4 -> "主机不可达";
            case 5 -> "目标拒绝连接";
            case 6 -> "TTL 已过期";
            case 7 -> "命令不受支持";
            case 8 -> "地址类型不受支持";
            default -> "错误码 " + code;
        };
    }

    private static void httpConnect(Socket socket, ProxySettings proxy,
                                    String host, int port, Deadline deadline) throws IOException {
        String authority = host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n")
                .append("Proxy-Connection: keep-alive\r\n");
        if (!proxy.username().isEmpty()) {
            String credential = proxy.username() + ":" + proxy.password();
            request.append("Proxy-Authorization: Basic ")
                    .append(Base64.getEncoder().encodeToString(
                            credential.getBytes(StandardCharsets.UTF_8)))
                    .append("\r\n");
        }
        request.append("\r\n");
        OutputStream output = socket.getOutputStream();
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();

        byte[] header = readHttpHeader(socket, socket.getInputStream(), deadline);
        String firstLine = new String(header, StandardCharsets.ISO_8859_1)
                .split("\\r?\\n", 2)[0];
        String[] parts = firstLine.strip().split("\\s+", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("HTTP CONNECT 代理响应无效");
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("HTTP CONNECT 状态码无效", e);
        }
        if (status < 200 || status >= 300) {
            throw new IOException(status == 407
                    ? "HTTP CONNECT 代理认证失败" : "HTTP CONNECT 返回状态 " + status);
        }
    }

    private static byte[] readHttpHeader(Socket socket, InputStream input, Deadline deadline)
            throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int state = 0;
        while (header.size() < MAX_HTTP_HEADER_BYTES) {
            socket.setSoTimeout(deadline.remainingMillis());
            int value = input.read();
            if (value < 0) {
                throw new EOFException("HTTP 响应提前结束");
            }
            header.write(value);
            state = switch (state) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> 4;
            };
            if (state == 4) {
                return header.toByteArray();
            }
        }
        throw new IOException("HTTP 响应头超过 16 KiB");
    }

    private static byte[] readExact(Socket socket, InputStream input,
                                    int length, Deadline deadline) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            socket.setSoTimeout(deadline.remainingMillis());
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new EOFException("代理响应提前结束");
            }
            offset += read;
        }
        return bytes;
    }

    private static ConnectionInfo connectionInfo(Socket socket, Route route,
                                                   ProxySettings proxy, String suffix) {
        String description = route == Route.SYSTEM
                ? "系统路由" : proxy.type().displayName() + " " + proxy.authority();
        if (suffix != null && !suffix.isBlank()) {
            description += " · " + suffix;
        }
        return connectionInfo(socket.getLocalAddress(), socket.getLocalPort(), description);
    }

    private static ConnectionInfo connectionInfo(DatagramSocket socket, String description) {
        return connectionInfo(socket.getLocalAddress(), socket.getLocalPort(), description);
    }

    private static ConnectionInfo connectionInfo(InetAddress address, int port,
                                                   String description) {
        String interfaceName = "未知网卡";
        try {
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
            if (networkInterface != null) {
                interfaceName = networkInterface.getDisplayName();
            }
        } catch (SocketException ignored) {
            // The local address remains useful when interface lookup is unavailable.
        }
        return new ConnectionInfo(address.getHostAddress(), port, interfaceName, description);
    }

    private static void validateTargets(List<Target> targets) {
        if (targets.isEmpty() || targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("监控目标数量必须为 1-" + MAX_TARGETS);
        }
        Set<String> ids = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        for (Target target : targets) {
            if (target == null || !ids.add(target.id())
                    || !addresses.add(target.protocol().name() + ":"
                    + target.host().toLowerCase(Locale.ROOT) + ":" + target.port()
                    + ":" + target.requestTarget())) {
                throw new IllegalArgumentException("监控目标 ID 或协议地址不能重复");
            }
        }
    }

    private static double elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    private static String readableError(Exception exception) {
        if (exception instanceof SocketTimeoutException) {
            return "响应超时";
        }
        if (exception instanceof UnknownHostException) {
            return "无法解析节点域名";
        }
        if (exception instanceof PortUnreachableException) {
            return "远端 UDP 端口不可达";
        }
        if (exception instanceof javax.net.ssl.SSLHandshakeException) {
            return "TLS 证书或握手验证失败";
        }
        if (exception instanceof SocketException && exception.getMessage() != null
                && exception.getMessage().toLowerCase(Locale.ROOT).contains("closed")) {
            return "探测已停止";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : sanitizeError(message);
    }

    private static String sanitizeError(String message) {
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').strip();
        return singleLine.length() <= 180 ? singleLine : singleLine.substring(0, 180) + "...";
    }

    public enum Protocol {
        HTTP("HTTP 请求"), HTTPS("HTTPS 请求"), TCP("TCP Connect"),
        TLS("TLS Handshake"), STUN_UDP("UDP / STUN");

        private final String displayName;

        Protocol(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum ProxyType {
        SOCKS5("SOCKS5"), HTTP_CONNECT("HTTP CONNECT");

        private final String displayName;

        ProxyType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum RoutePlan {
        SYSTEM_ONLY("仅系统路由", true, false),
        PROXY_ONLY("仅显式代理", false, true),
        COMPARE("系统路由 / 代理对比", true, true);

        private final String displayName;
        private final boolean systemRoute;
        private final boolean proxy;

        RoutePlan(String displayName, boolean systemRoute, boolean proxy) {
            this.displayName = displayName;
            this.systemRoute = systemRoute;
            this.proxy = proxy;
        }

        public String displayName() {
            return displayName;
        }

        public boolean usesSystemRoute() {
            return systemRoute;
        }

        public boolean usesProxy() {
            return proxy;
        }
    }

    public enum Route {
        SYSTEM("系统路由"), PROXY("显式代理");

        private final String displayName;

        Route(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum Quality {
        WAITING("等待"), EXCELLENT("优秀"), GOOD("良好"), DEGRADED("波动"),
        POOR("较差"), OFFLINE("离线"), UNSUPPORTED("不支持");

        private final String displayName;

        Quality(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record Target(String id, String name, Protocol protocol,
                         String host, int port, String requestTarget,
                         boolean enabled) {
        public Target(String id, String name, Protocol protocol,
                      String host, int port, boolean enabled) {
            this(id, name, protocol, host, port, "/", enabled);
        }

        public Target {
            id = requireText(id, "目标 ID");
            name = requireText(name, "目标名称");
            protocol = Objects.requireNonNull(protocol, "protocol");
            host = requireText(host, "目标地址");
            requestTarget = requestTarget == null || requestTarget.isBlank()
                    ? "/" : requestTarget.strip();
            if (id.length() > 64 || name.length() > 32 || host.length() > 253
                    || host.chars().anyMatch(Character::isWhitespace)
                    || requestTarget.length() > 2_048
                    || requestTarget.indexOf('\r') >= 0 || requestTarget.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("监控目标字段长度或格式无效");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("端口必须为 1-65535");
            }
            if (protocol == Protocol.HTTP || protocol == Protocol.HTTPS) {
                if (!requestTarget.startsWith("/")) {
                    throw new IllegalArgumentException("HTTP 请求路径必须以 / 开头");
                }
            } else {
                requestTarget = "/";
            }
        }
    }

    public record TargetEndpoint(Protocol protocol, String host, int port,
                                 String requestTarget) {
        public TargetEndpoint {
            protocol = Objects.requireNonNull(protocol, "protocol");
            host = requireText(host, "目标地址");
            if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
                host = host.substring(1, host.length() - 1);
            }
            requestTarget = requestTarget == null || requestTarget.isBlank()
                    ? "/" : requestTarget;
            new Target("validation", "validation", protocol, host, port,
                    requestTarget, true);
        }

        public static TargetEndpoint parse(String input, Protocol fallbackProtocol) {
            String value = requireText(input, "监控端点");
            Protocol selectedProtocol = Objects.requireNonNull(fallbackProtocol,
                    "fallbackProtocol");
            boolean explicitScheme = value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$");
            URI uri;
            try {
                uri = new URI(explicitScheme ? value : "probe://" + value);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("监控端点格式无效", e);
            }
            if (uri.getUserInfo() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("监控端点不能包含凭据或片段");
            }
            String scheme = uri.getScheme() == null
                    ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            Protocol endpointProtocol = switch (scheme) {
                case "http" -> Protocol.HTTP;
                case "https" -> Protocol.HTTPS;
                case "tcp" -> Protocol.TCP;
                case "tls" -> Protocol.TLS;
                case "stun", "udp" -> Protocol.STUN_UDP;
                case "probe" -> selectedProtocol;
                default -> throw new IllegalArgumentException(
                        "支持 http://、https://、tcp://、tls:// 和 stun:// 端点");
            };
            String endpointHost = uri.getHost();
            if (endpointHost == null || endpointHost.isBlank()) {
                throw new IllegalArgumentException("目标主机名或 IP 无效");
            }
            int endpointPort = uri.getPort() >= 0
                    ? uri.getPort() : defaultPort(endpointProtocol);
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            boolean http = endpointProtocol == Protocol.HTTP
                    || endpointProtocol == Protocol.HTTPS;
            if (!http && ((path != null && !path.isEmpty() && !"/".equals(path))
                    || query != null)) {
                throw new IllegalArgumentException("该协议端点不能包含路径或查询参数");
            }
            String requestTarget = path == null || path.isEmpty() ? "/" : path;
            if (query != null && !query.isEmpty()) {
                requestTarget += "?" + query;
            }
            return new TargetEndpoint(endpointProtocol, endpointHost, endpointPort,
                    requestTarget);
        }

        public String displayValue() {
            String scheme = switch (protocol) {
                case HTTP -> "http";
                case HTTPS -> "https";
                case TCP -> "tcp";
                case TLS -> "tls";
                case STUN_UDP -> "stun";
            };
            String authority = host.contains(":") ? "[" + host + "]" : host;
            String path = protocol == Protocol.HTTP || protocol == Protocol.HTTPS
                    ? requestTarget : "";
            return scheme + "://" + authority + ":" + port + path;
        }

        public String defaultName() {
            String value = host + " " + protocol.displayName();
            return value.length() <= 32 ? value : value.substring(0, 32);
        }

        private static int defaultPort(Protocol protocol) {
            return switch (protocol) {
                case HTTP -> 80;
                case HTTPS, TLS -> 443;
                case TCP -> throw new IllegalArgumentException("TCP 端点必须填写端口");
                case STUN_UDP -> 3478;
            };
        }
    }

    public record ProxyEndpoint(ProxyType type, String host, int port) {
        public ProxyEndpoint {
            type = Objects.requireNonNull(type, "type");
            host = requireText(host, "代理地址");
            if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
                host = host.substring(1, host.length() - 1);
            }
            if (host.length() > 253 || host.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("代理地址格式无效");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("代理端口必须为 1-65535");
            }
        }

        public static ProxyEndpoint parse(String input, ProxyType fallbackType,
                                          int fallbackPort) {
            String value = requireText(input, "代理地址");
            ProxyType selectedType = Objects.requireNonNull(fallbackType, "fallbackType");
            boolean explicitScheme = value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$");
            URI uri;
            try {
                uri = new URI(explicitScheme ? value : "proxy://" + value);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("代理地址格式无效", e);
            }

            String scheme = uri.getScheme() == null
                    ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            ProxyType endpointType = switch (scheme) {
                case "http" -> ProxyType.HTTP_CONNECT;
                case "socks", "socks5" -> ProxyType.SOCKS5;
                case "proxy" -> selectedType;
                default -> throw new IllegalArgumentException(
                        "仅支持 http://、socks5:// 或主机:端口格式");
            };
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException("请在用户名和密码字段中填写代理凭据");
            }
            String path = uri.getRawPath();
            if ((path != null && !path.isEmpty() && !"/".equals(path))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("代理地址不能包含路径、查询参数或片段");
            }
            String endpointHost = uri.getHost();
            if (endpointHost == null || endpointHost.isBlank()) {
                throw new IllegalArgumentException("代理主机名或 IP 无效");
            }
            int endpointPort = uri.getPort();
            if (endpointPort < 0) {
                endpointPort = explicitScheme
                        ? endpointType == ProxyType.HTTP_CONNECT ? 80 : 1_080
                        : fallbackPort;
            }
            return new ProxyEndpoint(endpointType, endpointHost, endpointPort);
        }
    }

    public record ProxySettings(ProxyType type, String host, int port,
                                 String username, String password) {
        public ProxySettings {
            type = Objects.requireNonNull(type, "type");
            host = requireText(host, "代理地址");
            if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
                host = host.substring(1, host.length() - 1);
            }
            username = username == null ? "" : username;
            password = password == null ? "" : password;
            if (host.length() > 253 || host.chars().anyMatch(Character::isWhitespace)
                    || username.length() > 128 || password.length() > 255) {
                throw new IllegalArgumentException("代理设置长度或格式无效");
            }
            if (username.isEmpty() && !password.isEmpty()) {
                throw new IllegalArgumentException("填写代理密码时必须同时填写用户名");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("代理端口必须为 1-65535");
            }
            if (type == ProxyType.SOCKS5
                    && (username.getBytes(StandardCharsets.UTF_8).length > 255
                    || password.getBytes(StandardCharsets.UTF_8).length > 255)) {
                throw new IllegalArgumentException("SOCKS5 用户名或密码不能超过 255 字节");
            }
        }

        public String displayValue() {
            return type.displayName() + " · " + authority();
        }

        public String authority() {
            return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
        }

        @Override
        public String toString() {
            return "ProxySettings[type=" + type + ", host=" + host + ", port=" + port
                    + ", username=" + username + ", password=<redacted>]";
        }
    }

    public record PublicMapping(String address, int port) {
        public PublicMapping {
            address = Objects.requireNonNull(address, "address");
        }

        public String displayValue() {
            return address.contains(":") ? "[" + address + "]:" + port : address + ":" + port;
        }
    }

    public record ConnectionInfo(String localAddress, int localPort,
                                 String interfaceName, String routeDescription) {
        public String displayValue() {
            return localAddress + ":" + localPort + " · " + interfaceName
                    + " · " + routeDescription;
        }
    }

    public record ProbeSample(Instant capturedAt, boolean success, double rttMillis) {
    }

    public record MonitorSnapshot(Target target, Route route, boolean supported,
                                   Quality quality, long sent, long received, long failed,
                                   long consecutiveFailures, double lastRttMillis,
                                   double averageRttMillis, double p95RttMillis,
                                   double peakRttMillis, double jitterMillis,
                                   double failurePercent, PublicMapping mapping,
                                  long mappingChanges, ConnectionInfo connection,
                                  String lastResponse, String lastError,
                                  List<ProbeSample> history) {
    }

    public record SessionSnapshot(boolean running, Instant startedAt, Duration elapsed,
                                  Duration interval, Duration timeout,
                                  RoutePlan routePlan, List<MonitorSnapshot> monitors) {
    }

    static record ProbeResult(boolean success, double rttMillis, PublicMapping mapping,
                              ConnectionInfo connection, String response, String error) {
        static ProbeResult success(double rttMillis, PublicMapping mapping,
                                   ConnectionInfo connection) {
            return success(rttMillis, mapping, connection, "");
        }

        static ProbeResult success(double rttMillis, PublicMapping mapping,
                                   ConnectionInfo connection, String response) {
            return new ProbeResult(true, rttMillis, mapping,
                    Objects.requireNonNull(connection), response == null ? "" : response, "");
        }

        static ProbeResult failure(String error) {
            return failure(error, "", null);
        }

        static ProbeResult failure(String error, String response,
                                   ConnectionInfo connection) {
            return new ProbeResult(false, 0, null, connection,
                    response == null ? "" : response,
                    error == null || error.isBlank() ? "未知错误" : error);
        }
    }

    static final class ProbeAccumulator {
        private final MonitorSpec spec;
        private final Deque<ProbeSample> history = new ArrayDeque<>();
        private long sent;
        private long received;
        private long failed;
        private long consecutiveFailures;
        private PublicMapping mapping;
        private long mappingChanges;
        private ConnectionInfo connection;
        private String lastResponse = "";
        private String lastError;

        ProbeAccumulator(MonitorSpec spec) {
            this.spec = spec;
            this.lastError = spec.unsupportedReason();
        }

        void accept(ProbeResult result) {
            sent++;
            Instant now = Instant.now();
            if (result.success()) {
                received++;
                consecutiveFailures = 0;
                if (mapping != null && result.mapping() != null
                        && !mapping.equals(result.mapping())) {
                    mappingChanges++;
                }
                if (result.mapping() != null) {
                    mapping = result.mapping();
                }
                connection = result.connection();
                lastResponse = result.response();
                lastError = "";
                addSample(new ProbeSample(now, true, result.rttMillis()));
            } else {
                failed++;
                consecutiveFailures++;
                if (result.connection() != null) {
                    connection = result.connection();
                }
                if (!result.response().isBlank()) {
                    lastResponse = result.response();
                }
                lastError = result.error();
                addSample(new ProbeSample(now, false, Double.NaN));
            }
        }

        MonitorSnapshot snapshot() {
            List<ProbeSample> recent = history.stream()
                    .skip(Math.max(0, history.size() - RECENT_QUALITY_SAMPLES)).toList();
            List<Double> successfulRtts = recent.stream().filter(ProbeSample::success)
                    .map(ProbeSample::rttMillis).toList();
            long recentReceived = successfulRtts.size();
            double average = successfulRtts.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(Double.NaN);
            double peak = successfulRtts.stream().mapToDouble(Double::doubleValue)
                    .max().orElse(Double.NaN);
            double jitter = calculateJitter(successfulRtts);
            double p95 = percentile95(successfulRtts);
            double failure = recent.isEmpty() ? 0
                    : (recent.size() - recentReceived) * 100.0 / recent.size();
            ProbeSample latest = history.peekLast();
            double lastRtt = latest != null && latest.success()
                    ? latest.rttMillis() : Double.NaN;
            Quality quality = spec.supported()
                    ? classify(recent.size(), recentReceived, consecutiveFailures,
                    average, jitter, failure)
                    : Quality.UNSUPPORTED;
            return new MonitorSnapshot(spec.target(), spec.route(), spec.supported(), quality,
                    sent, received, failed, consecutiveFailures, lastRtt, average, p95,
                    peak, jitter, failure, mapping, mappingChanges, connection,
                    lastResponse, lastError,
                    List.copyOf(history));
        }

        private static double calculateJitter(List<Double> values) {
            if (values.isEmpty()) {
                return Double.NaN;
            }
            if (values.size() == 1) {
                return 0;
            }
            double total = 0;
            for (int index = 1; index < values.size(); index++) {
                total += Math.abs(values.get(index) - values.get(index - 1));
            }
            return total / (values.size() - 1);
        }

        private static double percentile95(List<Double> values) {
            if (values.isEmpty()) {
                return Double.NaN;
            }
            List<Double> sorted = values.stream().sorted().toList();
            int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
            return sorted.get(index);
        }

        private void addSample(ProbeSample sample) {
            history.addLast(sample);
            while (history.size() > MAX_HISTORY_SAMPLES) {
                history.removeFirst();
            }
        }

        private static Quality classify(long sent, long received, long consecutiveFailures,
                                        double average, double jitter, double failure) {
            if (sent == 0) {
                return Quality.WAITING;
            }
            if (received == 0 || consecutiveFailures >= 3) {
                return Quality.OFFLINE;
            }
            if (failure >= 20 || average >= 800 || jitter >= 200) {
                return Quality.POOR;
            }
            if (failure >= 5 || average >= 350 || jitter >= 80) {
                return Quality.DEGRADED;
            }
            if (failure >= 1 || average >= 180 || jitter >= 30) {
                return Quality.GOOD;
            }
            return Quality.EXCELLENT;
        }
    }

    static record MonitorSpec(Target target, Route route,
                              boolean supported, String unsupportedReason) {
    }

    private static final class MonitorState implements AutoCloseable {
        private final MonitorSpec spec;
        private final ProbeAccumulator statistics;
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private volatile StunChannel stunChannel;
        private int consecutiveChannelFailures;

        private MonitorState(MonitorSpec spec) {
            this.spec = spec;
            this.statistics = new ProbeAccumulator(spec);
        }

        private ProbeResult probe(ProxySettings proxy, int timeoutMillis) throws IOException {
            if (spec.target().protocol() == Protocol.STUN_UDP) {
                try {
                    ProbeResult result = channel(proxy, timeoutMillis).probe(timeoutMillis);
                    consecutiveChannelFailures = 0;
                    return result;
                } catch (IOException e) {
                    consecutiveChannelFailures++;
                    if (consecutiveChannelFailures >= 3) {
                        resetChannel();
                        consecutiveChannelFailures = 0;
                    }
                    throw e;
                }
            }
            return probeStream(spec.target(), spec.route(), proxy, timeoutMillis,
                    spec.target().protocol() == Protocol.TLS
                            || spec.target().protocol() == Protocol.HTTPS);
        }

        private synchronized StunChannel channel(ProxySettings proxy, int timeoutMillis)
                throws IOException {
            if (stunChannel == null) {
                stunChannel = spec.route() == Route.SYSTEM
                        ? new DirectStunChannel(spec.target())
                        : new Socks5StunChannel(spec.target(), proxy, timeoutMillis);
            }
            return stunChannel;
        }

        private synchronized MonitorSnapshot snapshot() {
            return statistics.snapshot();
        }

        private synchronized void resetChannel() {
            if (stunChannel != null) {
                stunChannel.close();
                stunChannel = null;
            }
        }

        @Override
        public synchronized void close() {
            resetChannel();
        }
    }

    private static final class Session implements AutoCloseable {
        private final long generation;
        private final Instant startedAt = Instant.now();
        private final RoutePlan routePlan;
        private final List<MonitorState> states;
        private final ProxySettings proxy;
        private final int intervalMillis;
        private final int timeoutMillis;
        private final Consumer<SessionSnapshot> listener;
        private final ScheduledExecutorService scheduler;
        private final ThreadPoolExecutor workers;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean snapshotScheduled = new AtomicBoolean();

        private Session(long generation, List<MonitorSpec> specs, ProxySettings proxy,
                        int intervalMillis, int timeoutMillis,
                        Consumer<SessionSnapshot> listener) {
            this.generation = generation;
            this.routePlan = routePlanOf(specs);
            this.states = specs.stream().map(MonitorState::new).toList();
            this.proxy = proxy;
            this.intervalMillis = intervalMillis;
            this.timeoutMillis = timeoutMillis;
            this.listener = listener;
            AtomicInteger threadNumber = new AtomicInteger();
            this.scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r ->
                    daemonThread(r, "NetworkQuality-Scheduler"));
            int supported = (int) specs.stream().filter(MonitorSpec::supported).count();
            int workerCount = Math.max(1, Math.min(MAX_WORKERS, supported));
            this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0L,
                    TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(Math.max(1, specs.size())),
                    runnable -> daemonThread(runnable,
                            "NetworkQuality-Probe-" + threadNumber.incrementAndGet()),
                    new ThreadPoolExecutor.AbortPolicy());
        }

        private SessionSnapshot snapshot(boolean running) {
            List<MonitorSnapshot> snapshots = new ArrayList<>(states.size());
            for (MonitorState state : states) {
                snapshots.add(state.snapshot());
            }
            return new SessionSnapshot(running, startedAt,
                    Duration.between(startedAt, Instant.now()),
                    Duration.ofMillis(intervalMillis), Duration.ofMillis(timeoutMillis), routePlan,
                    List.copyOf(snapshots));
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            scheduler.shutdownNow();
            states.forEach(MonitorState::close);
            workers.shutdownNow();
        }

        private static RoutePlan routePlanOf(List<MonitorSpec> specs) {
            boolean system = specs.stream().anyMatch(spec -> spec.route() == Route.SYSTEM);
            boolean proxy = specs.stream().anyMatch(spec -> spec.route() == Route.PROXY);
            return system && proxy ? RoutePlan.COMPARE
                    : proxy ? RoutePlan.PROXY_ONLY : RoutePlan.SYSTEM_ONLY;
        }
    }

    private interface StunChannel extends AutoCloseable {
        ProbeResult probe(int timeoutMillis) throws IOException;

        @Override
        void close();
    }

    private static final class DirectStunChannel implements StunChannel {
        private final Target target;
        private final DatagramSocket socket;

        private DirectStunChannel(Target target) throws IOException {
            this.target = target;
            this.socket = new DatagramSocket();
            socket.connect(InetAddress.getByName(target.host()), target.port());
        }

        @Override
        public ProbeResult probe(int timeoutMillis) throws IOException {
            long started = System.nanoTime();
            StunRequest request = createStunRequest();
            socket.setSoTimeout(timeoutMillis);
            socket.send(new DatagramPacket(request.packet(), request.packet().length));
            PublicMapping mapping = receiveDirectStun(socket, request.transactionId(),
                    started, timeoutMillis);
            return ProbeResult.success(elapsedMillis(started), mapping,
                    connectionInfo(socket, "系统路由"));
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    private static final class Socks5StunChannel implements StunChannel {
        private final Target target;
        private final ProxySettings proxy;
        private final Socket controlSocket;
        private final DatagramSocket dataSocket;
        private final InetSocketAddress relay;

        private Socks5StunChannel(Target target, ProxySettings proxy, int timeoutMillis)
                throws IOException {
            this.target = target;
            this.proxy = Objects.requireNonNull(proxy, "proxy");
            Deadline deadline = new Deadline(timeoutMillis);
            Socket control = connectSocket(proxy.host(), proxy.port(), deadline);
            DatagramSocket data = null;
            boolean success = false;
            try {
                InetSocketAddress relayAddress = socks5Command(control, proxy, 0x03,
                        "0.0.0.0", 0, deadline);
                if (relayAddress.getPort() == 0) {
                    throw new IOException("SOCKS5 UDP 中继端口无效");
                }
                data = new DatagramSocket();
                data.connect(relayAddress);
                this.controlSocket = control;
                this.dataSocket = data;
                this.relay = relayAddress;
                success = true;
            } finally {
                if (!success) {
                    control.close();
                    if (data != null) {
                        data.close();
                    }
                }
            }
        }

        @Override
        public ProbeResult probe(int timeoutMillis) throws IOException {
            long started = System.nanoTime();
            StunRequest request = createStunRequest();
            byte[] packet = wrapSocksUdp(target.host(), target.port(), request.packet());
            dataSocket.setSoTimeout(timeoutMillis);
            dataSocket.send(new DatagramPacket(packet, packet.length));
            long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            byte[] responseBuffer = new byte[MAX_UDP_PACKET_BYTES];
            while (true) {
                int remaining = remainingMillis(deadline);
                dataSocket.setSoTimeout(remaining);
                DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
                dataSocket.receive(response);
                int payloadOffset = socksUdpPayloadOffset(response.getData(), response.getLength());
                byte[] stunPacket = Arrays.copyOfRange(response.getData(), payloadOffset,
                        response.getLength());
                try {
                    PublicMapping mapping = decodeStunResponse(stunPacket, stunPacket.length,
                            request.transactionId());
                    return ProbeResult.success(elapsedMillis(started), mapping,
                             connectionInfo(dataSocket, proxy.type().displayName() + " "
                                     + proxy.authority() + " · UDP 中继 "
                                     + relay.getAddress().getHostAddress() + ":" + relay.getPort()));
                } catch (TransactionMismatchException ignored) {
                    // Ignore a delayed response from the previous transaction.
                }
            }
        }

        @Override
        public void close() {
            dataSocket.close();
            try {
                controlSocket.close();
            } catch (IOException ignored) {
                // Datagram closure already stops active probes.
            }
        }
    }

    private static byte[] wrapSocksUdp(String host, int port, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeShort(0);
        output.writeByte(0);
        writeSocksAddress(output, host, port);
        output.write(payload);
        return bytes.toByteArray();
    }

    private static int socksUdpPayloadOffset(byte[] packet, int length) throws IOException {
        if (length < 7 || packet[0] != 0 || packet[1] != 0) {
            throw new IOException("SOCKS5 UDP 响应格式无效");
        }
        if (packet[2] != 0) {
            throw new IOException("SOCKS5 UDP 分片不受支持");
        }
        int addressType = Byte.toUnsignedInt(packet[3]);
        int offset = switch (addressType) {
            case 0x01 -> 4 + 4;
            case 0x04 -> 4 + 16;
            case 0x03 -> {
                int nameLength = Byte.toUnsignedInt(packet[4]);
                yield 5 + nameLength;
            }
            default -> throw new IOException("SOCKS5 UDP 地址类型无效");
        };
        int payloadOffset = offset + 2;
        if (payloadOffset > length) {
            throw new IOException("SOCKS5 UDP 响应长度无效");
        }
        return payloadOffset;
    }

    private static PublicMapping receiveDirectStun(DatagramSocket socket, byte[] transactionId,
                                                    long started, int timeoutMillis)
            throws IOException {
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        byte[] responseBuffer = new byte[MAX_UDP_PACKET_BYTES];
        while (true) {
            socket.setSoTimeout(remainingMillis(deadline));
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            try {
                return decodeStunResponse(response.getData(), response.getLength(), transactionId);
            } catch (TransactionMismatchException ignored) {
                // Ignore a delayed response from the previous transaction.
            }
        }
    }

    static StunRequest createStunRequest() {
        byte[] transactionId = new byte[12];
        RANDOM.nextBytes(transactionId);
        ByteBuffer buffer = ByteBuffer.allocate(STUN_HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort((short) STUN_BINDING_REQUEST);
        buffer.putShort((short) 0);
        buffer.putInt(STUN_MAGIC_COOKIE);
        buffer.put(transactionId);
        return new StunRequest(buffer.array(), transactionId);
    }

    static PublicMapping decodeStunResponse(byte[] packet, int length, byte[] transactionId)
            throws IOException {
        if (packet == null || transactionId == null || transactionId.length != 12
                || length < STUN_HEADER_LENGTH || length > packet.length) {
            throw new IOException("STUN 响应格式无效");
        }
        ByteBuffer buffer = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.BIG_ENDIAN);
        int messageType = Short.toUnsignedInt(buffer.getShort());
        int messageLength = Short.toUnsignedInt(buffer.getShort());
        int magicCookie = buffer.getInt();
        byte[] responseTransactionId = new byte[12];
        buffer.get(responseTransactionId);
        if (magicCookie != STUN_MAGIC_COOKIE) {
            throw new IOException("STUN magic cookie 无效");
        }
        if (!Arrays.equals(transactionId, responseTransactionId)) {
            throw new TransactionMismatchException();
        }
        int messageEnd = STUN_HEADER_LENGTH + messageLength;
        if (messageEnd > length) {
            throw new IOException("STUN 响应长度不完整");
        }
        if (messageType == STUN_BINDING_ERROR) {
            throw new IOException(readStunError(buffer, messageEnd));
        }
        if (messageType != STUN_BINDING_SUCCESS) {
            throw new IOException("不是 STUN Binding 成功响应");
        }
        PublicMapping mappedAddress = null;
        while (buffer.position() + 4 <= messageEnd) {
            int type = Short.toUnsignedInt(buffer.getShort());
            int attributeLength = Short.toUnsignedInt(buffer.getShort());
            int valueStart = buffer.position();
            if (valueStart + attributeLength > messageEnd) {
                throw new IOException("STUN 属性长度无效");
            }
            if (type == ATTR_XOR_MAPPED_ADDRESS || type == ATTR_MAPPED_ADDRESS) {
                PublicMapping mapping = decodeMappedAddress(packet, valueStart, attributeLength,
                        type == ATTR_XOR_MAPPED_ADDRESS, transactionId);
                if (type == ATTR_XOR_MAPPED_ADDRESS) {
                    return mapping;
                }
                mappedAddress = mapping;
            }
            int nextAttribute = valueStart + paddedLength(attributeLength);
            if (nextAttribute > messageEnd) {
                throw new IOException("STUN 属性填充长度无效");
            }
            buffer.position(nextAttribute);
        }
        if (mappedAddress != null) {
            return mappedAddress;
        }
        throw new IOException("STUN 响应未包含公网映射地址");
    }

    private static PublicMapping decodeMappedAddress(byte[] packet, int offset, int length,
                                                       boolean xor, byte[] transactionId)
            throws IOException {
        if (length < 8 || offset + length > packet.length) {
            throw new IOException("STUN 映射地址属性无效");
        }
        int family = Byte.toUnsignedInt(packet[offset + 1]);
        int addressLength = family == 0x01 ? 4 : family == 0x02 ? 16 : -1;
        if (addressLength < 0 || length < 4 + addressLength) {
            throw new IOException("STUN 地址族不受支持");
        }
        int port = ((packet[offset + 2] & 0xff) << 8) | (packet[offset + 3] & 0xff);
        if (xor) {
            port ^= STUN_MAGIC_COOKIE >>> 16;
        }
        byte[] addressBytes = Arrays.copyOfRange(packet, offset + 4, offset + 4 + addressLength);
        if (xor) {
            byte[] mask = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                    .putInt(STUN_MAGIC_COOKIE).put(transactionId).array();
            for (int index = 0; index < addressBytes.length; index++) {
                addressBytes[index] ^= mask[index];
            }
        }
        return new PublicMapping(InetAddress.getByAddress(addressBytes).getHostAddress(), port);
    }

    private static String readStunError(ByteBuffer buffer, int messageEnd) {
        while (buffer.position() + 4 <= messageEnd) {
            int type = Short.toUnsignedInt(buffer.getShort());
            int length = Short.toUnsignedInt(buffer.getShort());
            int valueStart = buffer.position();
            if (valueStart + length > messageEnd) {
                break;
            }
            if (type == ATTR_ERROR_CODE && length >= 4) {
                int code = (buffer.get(valueStart + 2) & 0x07) * 100
                        + Byte.toUnsignedInt(buffer.get(valueStart + 3));
                return "STUN 服务器返回错误 " + code;
            }
            int nextAttribute = valueStart + paddedLength(length);
            if (nextAttribute > messageEnd) {
                break;
            }
            buffer.position(nextAttribute);
        }
        return "STUN 服务器返回错误响应";
    }

    private static int paddedLength(int length) {
        return (length + 3) & ~3;
    }

    private static int remainingMillis(long deadlineNanos) throws SocketTimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("响应超时");
        }
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return normalized;
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    static record StunRequest(byte[] packet, byte[] transactionId) {
    }

    private static final class Deadline {
        private final long deadlineNanos;

        private Deadline(int timeoutMillis) {
            this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        }

        private int remainingMillis() throws SocketTimeoutException {
            return NetworkQualityService.remainingMillis(deadlineNanos);
        }
    }

    private static final class TransactionMismatchException extends IOException {
    }
}
