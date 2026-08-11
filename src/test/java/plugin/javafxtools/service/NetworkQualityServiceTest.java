package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.service.NetworkQualityService.ConnectionInfo;
import plugin.javafxtools.service.NetworkQualityService.MonitorSnapshot;
import plugin.javafxtools.service.NetworkQualityService.MonitorSpec;
import plugin.javafxtools.service.NetworkQualityService.ProbeAccumulator;
import plugin.javafxtools.service.NetworkQualityService.ProbeResult;
import plugin.javafxtools.service.NetworkQualityService.Protocol;
import plugin.javafxtools.service.NetworkQualityService.ProxyEndpoint;
import plugin.javafxtools.service.NetworkQualityService.ProxySettings;
import plugin.javafxtools.service.NetworkQualityService.ProxyType;
import plugin.javafxtools.service.NetworkQualityService.PublicMapping;
import plugin.javafxtools.service.NetworkQualityService.Quality;
import plugin.javafxtools.service.NetworkQualityService.Route;
import plugin.javafxtools.service.NetworkQualityService.RoutePlan;
import plugin.javafxtools.service.NetworkQualityService.StunRequest;
import plugin.javafxtools.service.NetworkQualityService.Target;
import plugin.javafxtools.service.NetworkQualityService.TargetEndpoint;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkQualityServiceTest {
    private static final int MAGIC_COOKIE = 0x2112A442;

    @Test
    void stunRequestAndXorMappingFollowRfc5389() throws Exception {
        StunRequest request = NetworkQualityService.createStunRequest();
        assertEquals(20, request.packet().length);
        assertEquals(12, request.transactionId().length);

        PublicMapping expected = new PublicMapping("203.0.113.24", 54_321);
        byte[] response = stunSuccessResponse(request.transactionId(), expected);

        assertEquals(expected, NetworkQualityService.decodeStunResponse(
                response, response.length, request.transactionId()));
    }

    @Test
    void decodesIpv6XorMapping() throws Exception {
        StunRequest request = NetworkQualityService.createStunRequest();
        String address = InetAddress.getByName("2001:db8::42").getHostAddress();
        PublicMapping expected = new PublicMapping(address, 40_000);
        byte[] response = stunIpv6SuccessResponse(request.transactionId(), expected);

        assertEquals(expected, NetworkQualityService.decodeStunResponse(
                response, response.length, request.transactionId()));
    }

    @Test
    void directUdpProbeReportsMappingAndSystemRoute() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0)) {
            PublicMapping expected = new PublicMapping("203.0.113.44", 45_000);
            Thread responder = daemon(() -> respondDirectStun(server, expected),
                    "Direct-Stun-Server");
            responder.start();
            Target target = target("direct-udp", Protocol.STUN_UDP,
                    "127.0.0.1", server.getLocalPort());

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.SYSTEM, null, 1_000);

            assertTrue(result.success());
            assertEquals(expected, result.mapping());
            assertEquals("系统路由", result.connection().routeDescription());
        }
    }

    @Test
    void statisticsTrackQualityConsecutiveFailuresAndBoundHistory() {
        Target target = target("stats", Protocol.TCP, "example.com", 443);
        ProbeAccumulator accumulator = new ProbeAccumulator(
                new MonitorSpec(target, Route.SYSTEM, true, ""));
        assertEquals("", accumulator.snapshot().lastResponse());
        ConnectionInfo connection = new ConnectionInfo("192.0.2.2", 50_000,
                "Test Adapter", "系统路由");

        accumulator.accept(ProbeResult.success(40, null, connection));
        accumulator.accept(ProbeResult.success(50, null, connection));
        accumulator.accept(ProbeResult.failure("响应超时"));
        accumulator.accept(ProbeResult.failure("响应超时"));
        accumulator.accept(ProbeResult.failure("响应超时"));

        MonitorSnapshot snapshot = accumulator.snapshot();
        assertEquals(5, snapshot.sent());
        assertEquals(2, snapshot.received());
        assertEquals(3, snapshot.failed());
        assertEquals(3, snapshot.consecutiveFailures());
        assertEquals(45, snapshot.averageRttMillis(), 0.01);
        assertEquals(50, snapshot.p95RttMillis(), 0.01);
        assertTrue(Double.isNaN(snapshot.lastRttMillis()));
        assertEquals(10, snapshot.jitterMillis(), 0.01);
        assertEquals(Quality.OFFLINE, snapshot.quality());

        for (int index = 0; index < 220; index++) {
            accumulator.accept(ProbeResult.success(20 + index % 3, null, connection));
        }
        assertEquals(NetworkQualityService.MAX_HISTORY_SAMPLES,
                accumulator.snapshot().history().size());
        assertEquals(0, accumulator.snapshot().failurePercent(), 0.01);
        assertEquals(Quality.EXCELLENT, accumulator.snapshot().quality());
    }

    @Test
    void recentMetricsAreUnavailableWhenTheRecentWindowContainsOnlyFailures() {
        ProbeAccumulator accumulator = new ProbeAccumulator(new MonitorSpec(
                target("recent-window", Protocol.TCP, "example.com", 443),
                Route.SYSTEM, true, ""));
        ConnectionInfo connection = new ConnectionInfo("192.0.2.2", 50_000,
                "Test Adapter", "系统路由");
        accumulator.accept(ProbeResult.success(42, null, connection));
        for (int index = 0; index < NetworkQualityService.RECENT_QUALITY_SAMPLES; index++) {
            accumulator.accept(ProbeResult.failure("响应超时"));
        }

        MonitorSnapshot snapshot = accumulator.snapshot();

        assertTrue(Double.isNaN(snapshot.averageRttMillis()));
        assertTrue(Double.isNaN(snapshot.p95RttMillis()));
        assertTrue(Double.isNaN(snapshot.peakRttMillis()));
        assertTrue(Double.isNaN(snapshot.jitterMillis()));
        assertEquals(100, snapshot.failurePercent(), 0.01);
        assertEquals(Quality.OFFLINE, snapshot.quality());
    }

    @Test
    void directTcpProbeUsesSystemRouteAndReportsLocalInterface() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = daemon(() -> acceptAndClose(server), "Direct-Tcp-Server");
            acceptor.start();
            Target target = target("tcp", Protocol.TCP, "127.0.0.1",
                    server.getLocalPort());

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.SYSTEM, null, 1_000);

            assertTrue(result.success());
            assertNotNull(result.connection());
            assertEquals("系统路由", result.connection().routeDescription());
            assertFalse(result.connection().localAddress().isBlank());
        }
    }

    @Test
    void parsesHttpAndAdvancedEndpointAddresses() {
        TargetEndpoint http = TargetEndpoint.parse(
                "http://127.0.0.1:10808/health?ready=true", Protocol.TLS);
        assertEquals(Protocol.HTTP, http.protocol());
        assertEquals("127.0.0.1", http.host());
        assertEquals(10_808, http.port());
        assertEquals("/health?ready=true", http.requestTarget());
        assertEquals("http://127.0.0.1:10808/health?ready=true", http.displayValue());

        TargetEndpoint https = TargetEndpoint.parse("https://example.com/status", Protocol.TCP);
        assertEquals(443, https.port());
        assertEquals(Protocol.HTTPS, https.protocol());
        TargetEndpoint ipv6 = TargetEndpoint.parse("tcp://[::1]:8443", Protocol.HTTP);
        assertEquals("::1", ipv6.host());
        assertEquals("tcp://[::1]:8443", ipv6.displayValue());
        assertThrows(IllegalArgumentException.class,
                () -> TargetEndpoint.parse("tcp://example.com/path", Protocol.TCP));
    }

    @Test
    void directHttpProbeRequestsConfiguredPathAndReportsStatus() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicReference<String> request = new AtomicReference<>();
            daemon(() -> serveHttpEndpoint(server, 204, "No Content", request),
                    "Fake-Http-Endpoint").start();
            Target target = new Target("http", "Local health", Protocol.HTTP,
                    "127.0.0.1", server.getLocalPort(), "/health?ready=true", true);

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.SYSTEM, null, 1_000);

            assertTrue(result.success());
            assertEquals("HTTP 204 No Content", result.response());
            assertTrue(request.get().startsWith("GET /health?ready=true HTTP/1.1"));
        }
    }

    @Test
    void serverErrorIsARecordedHttpFailureWithResponseDetails() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            daemon(() -> serveHttpEndpoint(server, 503, "Service Unavailable",
                    new AtomicReference<>()), "Failing-Http-Endpoint").start();
            Target target = new Target("http-503", "Unavailable", Protocol.HTTP,
                    "127.0.0.1", server.getLocalPort(), "/health", true);

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.SYSTEM, null, 1_000);

            assertFalse(result.success());
            assertEquals("HTTP 503 Service Unavailable", result.response());
            assertTrue(result.error().contains("HTTP 503 Service Unavailable"));
            ProbeAccumulator accumulator = new ProbeAccumulator(
                    new MonitorSpec(target, Route.SYSTEM, true, ""));
            accumulator.accept(result);
            assertEquals("HTTP 503 Service Unavailable",
                    accumulator.snapshot().lastResponse());
            assertEquals(1, accumulator.snapshot().failed());
        }
    }

    @Test
    void httpEndpointCanBeMonitoredThroughHttpConnectProxy() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket(0)) {
            AtomicReference<String> requests = new AtomicReference<>();
            daemon(() -> serveHttpTunnel(proxyServer, requests),
                    "Fake-Http-Tunnel").start();
            ProxySettings settings = new ProxySettings(ProxyType.HTTP_CONNECT,
                    "127.0.0.1", proxyServer.getLocalPort(), "", "");
            Target target = new Target("proxied-http", "Proxied health", Protocol.HTTP,
                    "service.internal", 8080, "/health", true);

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.PROXY, settings, 1_000);

            assertTrue(result.success());
            assertEquals("HTTP 204 No Content", result.response());
            assertTrue(requests.get().contains("CONNECT service.internal:8080 HTTP/1.1"));
            assertTrue(requests.get().contains("GET /health HTTP/1.1"));
        }
    }

    @Test
    void clientErrorIsNotReportedAsHealthyAvailability() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            daemon(() -> serveHttpEndpoint(server, 404, "Not Found",
                    new AtomicReference<>()), "Missing-Http-Endpoint").start();
            Target target = new Target("http-404", "Missing", Protocol.HTTP,
                    "127.0.0.1", server.getLocalPort(), "/missing", true);

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.SYSTEM, null, 1_000);

            assertFalse(result.success());
            assertEquals("HTTP 404 Not Found", result.response());
        }
    }

    @Test
    void informationalHttpResponseIsNotReportedAsHealthyAvailability() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            daemon(() -> serveHttpEndpoint(server, 103, "Early Hints",
                    new AtomicReference<>()), "Informational-Http-Endpoint").start();
            Target target = new Target("http-103", "Informational", Protocol.HTTP,
                    "127.0.0.1", server.getLocalPort(), "/health", true);

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.SYSTEM, null, 1_000);

            assertFalse(result.success());
            assertEquals("HTTP 103 Early Hints", result.response());
        }
    }

    @Test
    void httpConnectProbeUsesTunnelAndSendsBasicAuthentication() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket(0)) {
            AtomicReference<String> request = new AtomicReference<>();
            Thread proxy = daemon(() -> serveHttpConnect(proxyServer, 200, request),
                    "Fake-Http-Proxy");
            proxy.start();
            ProxyEndpoint endpoint = ProxyEndpoint.parse(
                    "http://127.0.0.1:" + proxyServer.getLocalPort(),
                    ProxyType.SOCKS5, 1080);
            ProxySettings settings = new ProxySettings(endpoint.type(), endpoint.host(),
                    endpoint.port(), "alice", "secret");
            Target target = target("http-proxy", Protocol.TCP, "target.example", 443);

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.PROXY, settings, 1_000);

            assertTrue(result.success());
            assertTrue(request.get().startsWith("CONNECT target.example:443 HTTP/1.1"));
            assertTrue(request.get().contains("Proxy-Authorization: Basic YWxpY2U6c2VjcmV0"));
            assertFalse(result.connection().routeDescription().contains("secret"));
        }
    }

    @Test
    void httpConnectAuthenticationFailureIsClearAndDoesNotLeakPassword() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket(0)) {
            Thread proxy = daemon(() -> serveHttpConnect(proxyServer, 407,
                    new AtomicReference<>()), "Rejecting-Http-Proxy");
            proxy.start();
            ProxySettings settings = new ProxySettings(ProxyType.HTTP_CONNECT,
                    "127.0.0.1", proxyServer.getLocalPort(), "alice", "top-secret");

            Exception failure = assertThrows(Exception.class, () ->
                    NetworkQualityService.probeOnce(
                            target("rejected", Protocol.TCP, "target.example", 443),
                            Route.PROXY, settings, 1_000));

            assertTrue(failure.getMessage().contains("认证失败"));
            assertFalse(failure.getMessage().contains("top-secret"));
        }
    }

    @Test
    void httpConnectResponseHeaderHasStrictSizeLimit() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket(0)) {
            Thread proxy = daemon(() -> serveOversizedHttpHeader(proxyServer),
                    "Oversized-Http-Proxy");
            proxy.start();
            ProxySettings settings = new ProxySettings(ProxyType.HTTP_CONNECT,
                    "127.0.0.1", proxyServer.getLocalPort(), "", "");

            Exception failure = assertThrows(Exception.class, () ->
                    NetworkQualityService.probeOnce(
                            target("oversized", Protocol.TCP, "target.example", 443),
                            Route.PROXY, settings, 1_000));

            assertTrue(failure.getMessage().contains("16 KiB"));
        }
    }

    @Test
    void socks5ConnectUsesRemoteDomainName() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket(0)) {
            AtomicReference<String> requestedHost = new AtomicReference<>();
            Thread proxy = daemon(() -> serveSocksConnect(proxyServer, requestedHost),
                    "Fake-Socks-Connect");
            proxy.start();
            ProxySettings settings = new ProxySettings(ProxyType.SOCKS5,
                    "127.0.0.1", proxyServer.getLocalPort(), "", "");
            Target target = target("socks", Protocol.TCP, "remote-dns.example", 443);

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.PROXY, settings, 1_000);

            assertTrue(result.success());
            assertEquals("remote-dns.example", requestedHost.get());
            assertTrue(result.connection().routeDescription().startsWith("SOCKS5"));
        }
    }

    @Test
    void proxyPasswordRequiresUsername() {
        assertThrows(IllegalArgumentException.class, () -> new ProxySettings(
                ProxyType.SOCKS5, "127.0.0.1", 1080, "", "secret"));
    }

    @Test
    void proxyEndpointAcceptsFullUrlsAndCanonicalizesHostAndPort() {
        assertEquals(new ProxyEndpoint(ProxyType.HTTP_CONNECT, "127.0.0.1", 10808),
                ProxyEndpoint.parse("http://127.0.0.1:10808",
                        ProxyType.SOCKS5, 1080));
        assertEquals(new ProxyEndpoint(ProxyType.SOCKS5, "::1", 1080),
                ProxyEndpoint.parse("socks5://[::1]:1080",
                        ProxyType.HTTP_CONNECT, 8080));
        assertEquals(new ProxyEndpoint(ProxyType.HTTP_CONNECT, "localhost", 8080),
                ProxyEndpoint.parse("localhost:8080",
                        ProxyType.HTTP_CONNECT, 3128));

        assertThrows(IllegalArgumentException.class, () -> ProxyEndpoint.parse(
                "https://127.0.0.1:10808", ProxyType.HTTP_CONNECT, 8080));
        assertThrows(IllegalArgumentException.class, () -> ProxyEndpoint.parse(
                "http://127.0.0.1:10808/path", ProxyType.HTTP_CONNECT, 8080));
    }

    @Test
    void proxySettingsRedactPasswordAndValidateSocksCredentialBytes() {
        ProxySettings settings = new ProxySettings(ProxyType.HTTP_CONNECT,
                "127.0.0.1", 10808, "alice", "top-secret");

        assertFalse(settings.toString().contains("top-secret"));
        assertTrue(settings.toString().contains("<redacted>"));
        assertThrows(IllegalArgumentException.class, () -> new ProxySettings(
                ProxyType.SOCKS5, "127.0.0.1", 1080, "中".repeat(86), ""));
    }

    @Test
    void socks5UsernamePasswordAuthenticationIsSupported() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket(0)) {
            AtomicReference<String> credentials = new AtomicReference<>();
            Thread proxy = daemon(() -> serveAuthenticatedSocksConnect(
                    proxyServer, credentials), "Authenticated-Socks-Connect");
            proxy.start();
            ProxySettings settings = new ProxySettings(ProxyType.SOCKS5,
                    "127.0.0.1", proxyServer.getLocalPort(), "alice", "secret");

            ProbeResult result = NetworkQualityService.probeOnce(
                    target("authenticated", Protocol.TCP, "target.example", 443),
                    Route.PROXY, settings, 1_000);

            assertTrue(result.success());
            assertEquals("alice:secret", credentials.get());
            assertFalse(result.connection().displayValue().contains("secret"));
        }
    }

    @Test
    void socks5CredentialsStillAllowProxyToSelectNoAuthentication() throws Exception {
        try (ServerSocket proxyServer = new ServerSocket(0)) {
            AtomicReference<String> requestedHost = new AtomicReference<>();
            Thread proxy = daemon(() -> serveSocksConnect(proxyServer, requestedHost),
                    "No-Auth-Socks-Connect");
            proxy.start();
            ProxySettings settings = new ProxySettings(ProxyType.SOCKS5,
                    "127.0.0.1", proxyServer.getLocalPort(), "alice", "secret");

            ProbeResult result = NetworkQualityService.probeOnce(
                    target("socks-no-auth", Protocol.TCP, "remote-dns.example", 443),
                    Route.PROXY, settings, 1_000);

            assertTrue(result.success());
            assertEquals("remote-dns.example", requestedHost.get());
        }
    }

    @Test
    void socks5UdpAssociateCarriesStunAndReturnsPublicMapping() throws Exception {
        try (ServerSocket controlServer = new ServerSocket(0);
             DatagramSocket relay = new DatagramSocket(0)) {
            PublicMapping expected = new PublicMapping("198.51.100.17", 45_678);
            CountDownLatch udpReceived = new CountDownLatch(1);
            Thread control = daemon(() -> serveSocksUdpControl(controlServer, relay),
                    "Fake-Socks-Udp-Control");
            Thread relayThread = daemon(() -> serveSocksUdpRelay(relay, expected, udpReceived),
                    "Fake-Socks-Udp-Relay");
            control.start();
            relayThread.start();
            ProxySettings settings = new ProxySettings(ProxyType.SOCKS5,
                    "127.0.0.1", controlServer.getLocalPort(), "", "");
            Target target = target("socks-udp", Protocol.STUN_UDP,
                    "stun.remote.example", 3478);

            ProbeResult result = NetworkQualityService.probeOnce(
                    target, Route.PROXY, settings, 1_500);

            assertTrue(result.success());
            assertEquals(expected, result.mapping());
            assertTrue(udpReceived.await(1, TimeUnit.SECONDS));
            assertTrue(result.connection().routeDescription().contains("UDP 中继"));
        }
    }

    @Test
    void httpConnectMarksUdpAsUnsupportedWithoutCountingFailure() throws Exception {
        assertFalse(NetworkQualityService.supports(
                Protocol.STUN_UDP, ProxyType.HTTP_CONNECT));
        assertTrue(NetworkQualityService.supports(Protocol.TCP, ProxyType.HTTP_CONNECT));
        assertTrue(NetworkQualityService.supports(Protocol.TLS, ProxyType.HTTP_CONNECT));

        try (NetworkQualityService service = new NetworkQualityService()) {
            CountDownLatch firstSnapshot = new CountDownLatch(1);
            AtomicReference<MonitorSnapshot> monitor = new AtomicReference<>();
            ProxySettings proxy = new ProxySettings(ProxyType.HTTP_CONNECT,
                    "127.0.0.1", 65_000, "", "");
            Target udp = target("udp", Protocol.STUN_UDP, "stun.example", 3478);
            Target tcp = new Target("disabled-tcp", "TCP", Protocol.TCP,
                    "example.com", 443, false);

            assertThrows(IllegalArgumentException.class, () -> service.start(
                    List.of(udp, tcp), RoutePlan.PROXY_ONLY, proxy,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), snapshot -> {
                        monitor.set(snapshot.monitors().getFirst());
                        firstSnapshot.countDown();
                    }));
            assertEquals(1, firstSnapshot.getCount());
        }
    }

    @Test
    void stoppingInflightProbeDoesNotCreateArtificialFailure() throws Exception {
        try (ServerSocket silentServer = new ServerSocket(0);
             NetworkQualityService service = new NetworkQualityService()) {
            CountDownLatch accepted = new CountDownLatch(1);
            Thread server = daemon(() -> acceptWithoutReply(silentServer, accepted),
                    "Silent-Tcp-Server");
            server.start();
            AtomicReference<MonitorSnapshot> stopped = new AtomicReference<>();
            Target tls = target("silent", Protocol.TLS, "127.0.0.1",
                    silentServer.getLocalPort());
            service.start(List.of(tls), RoutePlan.SYSTEM_ONLY, null,
                    Duration.ofSeconds(1), Duration.ofSeconds(5), snapshot -> {
                        if (!snapshot.running()) {
                            stopped.set(snapshot.monitors().getFirst());
                        }
                    });

            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            service.stop();

            assertNotNull(stopped.get());
            assertEquals(0, stopped.get().sent());
            assertEquals(0, stopped.get().failed());
        }
    }

    private static Target target(String id, Protocol protocol, String host, int port) {
        return new Target(id, id, protocol, host, port, true);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void acceptAndClose(ServerSocket server) {
        try (Socket ignored = server.accept()) {
            // A completed accept is sufficient for a TCP connect probe.
        } catch (Exception ignored) {
        }
    }

    private static void acceptWithoutReply(ServerSocket server, CountDownLatch accepted) {
        try (Socket ignored = server.accept()) {
            accepted.countDown();
            Thread.sleep(5_000);
        } catch (Exception ignored) {
        }
    }

    private static void serveHttpConnect(ServerSocket server, int status,
                                         AtomicReference<String> request) {
        try (Socket socket = server.accept()) {
            request.set(readHeader(socket.getInputStream()));
            String reason = status == 200 ? "Connection Established" : "Proxy Authentication Required";
            socket.getOutputStream().write(("HTTP/1.1 " + status + " " + reason
                    + "\r\nContent-Length: 0\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        } catch (Exception ignored) {
        }
    }

    private static void serveHttpEndpoint(ServerSocket server, int status, String reason,
                                          AtomicReference<String> request) {
        try (Socket socket = server.accept()) {
            request.set(readHeader(socket.getInputStream()));
            socket.getOutputStream().write(("HTTP/1.1 " + status + " " + reason
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        } catch (Exception ignored) {
        }
    }

    private static void serveHttpTunnel(ServerSocket server,
                                        AtomicReference<String> requests) {
        try (Socket socket = server.accept()) {
            String connect = readHeader(socket.getInputStream());
            socket.getOutputStream().write(("HTTP/1.1 200 Connection Established\r\n"
                    + "Content-Length: 0\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            String endpoint = readHeader(socket.getInputStream());
            requests.set(connect + "\n" + endpoint);
            socket.getOutputStream().write(("HTTP/1.1 204 No Content\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        } catch (Exception ignored) {
        }
    }

    private static void serveOversizedHttpHeader(ServerSocket server) {
        try (Socket socket = server.accept()) {
            readHeader(socket.getInputStream());
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nX-Fill: "
                    + "a".repeat(17_000)).getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        } catch (Exception ignored) {
        }
    }

    private static String readHeader(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (state != 4 && bytes.size() < 16_384) {
            int value = input.read();
            if (value < 0) {
                break;
            }
            bytes.write(value);
            state = switch (state) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> 4;
            };
        }
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static void serveSocksConnect(ServerSocket server,
                                          AtomicReference<String> requestedHost) {
        try (Socket socket = server.accept()) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            completeSocksGreeting(input, output);
            byte[] header = input.readNBytes(4);
            assertEquals(0x01, Byte.toUnsignedInt(header[1]));
            requestedHost.set(readSocksAddress(input, header[3]));
            input.readUnsignedShort();
            output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0});
            output.flush();
        } catch (Exception ignored) {
        }
    }

    private static void serveAuthenticatedSocksConnect(ServerSocket server,
                                                       AtomicReference<String> credentials) {
        try (Socket socket = server.accept()) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            assertEquals(0x05, input.readUnsignedByte());
            int methods = input.readUnsignedByte();
            assertTrue(Arrays.equals(new byte[]{0x00, 0x02}, input.readNBytes(methods)));
            output.write(new byte[]{0x05, 0x02});
            output.flush();
            input.readUnsignedByte();
            String username = new String(input.readNBytes(input.readUnsignedByte()),
                    StandardCharsets.UTF_8);
            String password = new String(input.readNBytes(input.readUnsignedByte()),
                    StandardCharsets.UTF_8);
            credentials.set(username + ":" + password);
            output.write(new byte[]{0x01, 0x00});
            output.flush();
            byte[] header = input.readNBytes(4);
            readSocksAddress(input, header[3]);
            input.readUnsignedShort();
            output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0});
            output.flush();
        } catch (Exception ignored) {
        }
    }

    private static void serveSocksUdpControl(ServerSocket server, DatagramSocket relay) {
        try (Socket socket = server.accept()) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            completeSocksGreeting(input, output);
            byte[] header = input.readNBytes(4);
            assertEquals(0x03, Byte.toUnsignedInt(header[1]));
            readSocksAddress(input, header[3]);
            input.readUnsignedShort();
            output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1,
                    (byte) (relay.getLocalPort() >>> 8), (byte) relay.getLocalPort()});
            output.flush();
            while (input.read() >= 0) {
                // Keep the UDP association alive until the client closes it.
            }
        } catch (Exception ignored) {
        }
    }

    private static void completeSocksGreeting(DataInputStream input, DataOutputStream output)
            throws Exception {
        assertEquals(0x05, input.readUnsignedByte());
        int methods = input.readUnsignedByte();
        input.readNBytes(methods);
        output.write(new byte[]{0x05, 0x00});
        output.flush();
    }

    private static String readSocksAddress(DataInputStream input, byte addressType)
            throws Exception {
        return switch (Byte.toUnsignedInt(addressType)) {
            case 0x01 -> java.net.InetAddress.getByAddress(input.readNBytes(4)).getHostAddress();
            case 0x04 -> java.net.InetAddress.getByAddress(input.readNBytes(16)).getHostAddress();
            case 0x03 -> new String(input.readNBytes(input.readUnsignedByte()),
                    StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException("unsupported address type");
        };
    }

    private static void serveSocksUdpRelay(DatagramSocket relay, PublicMapping mapping,
                                           CountDownLatch received) {
        try {
            byte[] buffer = new byte[4_096];
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            relay.receive(request);
            int payloadOffset = socksPayloadOffset(request.getData(), request.getLength());
            byte[] transactionId = Arrays.copyOfRange(request.getData(),
                    payloadOffset + 8, payloadOffset + 20);
            byte[] stun = stunSuccessResponse(transactionId, mapping);
            byte[] response = socksUdpPacket("stun.remote.example", 3478, stun);
            relay.send(new DatagramPacket(response, response.length,
                    request.getAddress(), request.getPort()));
            received.countDown();
        } catch (Exception ignored) {
        }
    }

    private static void respondDirectStun(DatagramSocket server, PublicMapping mapping) {
        try {
            byte[] buffer = new byte[512];
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            server.receive(request);
            byte[] transactionId = Arrays.copyOfRange(request.getData(), 8, 20);
            byte[] response = stunSuccessResponse(transactionId, mapping);
            server.send(new DatagramPacket(response, response.length,
                    request.getAddress(), request.getPort()));
        } catch (Exception ignored) {
        }
    }

    private static int socksPayloadOffset(byte[] packet, int length) {
        int addressType = Byte.toUnsignedInt(packet[3]);
        int offset = switch (addressType) {
            case 0x01 -> 8;
            case 0x04 -> 20;
            case 0x03 -> 5 + Byte.toUnsignedInt(packet[4]);
            default -> throw new IllegalArgumentException("unsupported address type");
        };
        int payloadOffset = offset + 2;
        if (payloadOffset > length) {
            throw new IllegalArgumentException("invalid packet");
        }
        return payloadOffset;
    }

    private static byte[] socksUdpPacket(String host, int port, byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        output.writeShort(0);
        output.writeByte(0);
        output.writeByte(3);
        output.writeByte(hostBytes.length);
        output.write(hostBytes);
        output.writeShort(port);
        output.write(payload);
        return bytes.toByteArray();
    }

    private static byte[] stunSuccessResponse(byte[] transactionId, PublicMapping mapping)
            throws Exception {
        byte[] address = java.net.InetAddress.getByName(mapping.address()).getAddress();
        ByteBuffer response = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        response.putShort((short) 0x0101);
        response.putShort((short) 12);
        response.putInt(MAGIC_COOKIE);
        response.put(transactionId);
        response.putShort((short) 0x0020);
        response.putShort((short) 8);
        response.put((byte) 0);
        response.put((byte) 0x01);
        response.putShort((short) (mapping.port() ^ (MAGIC_COOKIE >>> 16)));
        byte[] cookie = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array();
        for (int index = 0; index < address.length; index++) {
            response.put((byte) (address[index] ^ cookie[index]));
        }
        return response.array();
    }

    private static byte[] stunIpv6SuccessResponse(byte[] transactionId, PublicMapping mapping)
            throws Exception {
        byte[] address = InetAddress.getByName(mapping.address()).getAddress();
        ByteBuffer response = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN);
        response.putShort((short) 0x0101);
        response.putShort((short) 24);
        response.putInt(MAGIC_COOKIE);
        response.put(transactionId);
        response.putShort((short) 0x0020);
        response.putShort((short) 20);
        response.put((byte) 0);
        response.put((byte) 0x02);
        response.putShort((short) (mapping.port() ^ (MAGIC_COOKIE >>> 16)));
        byte[] mask = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC_COOKIE).put(transactionId).array();
        for (int index = 0; index < address.length; index++) {
            response.put((byte) (address[index] ^ mask[index]));
        }
        return response.array();
    }
}
