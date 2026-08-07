package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkDiagnosticServiceTest {
    private final NetworkDiagnosticService service = new NetworkDiagnosticService();

    @Test
    void parsesUrlsHostPortsAndBracketedIpv6() {
        var https = NetworkDiagnosticService.parseTarget(
                "https://example.com/path?q=1", "");
        var overridden = NetworkDiagnosticService.parseTarget(
                "example.com:8080", "8443");
        var ipv6 = NetworkDiagnosticService.parseTarget("[::1]:9090", null);

        assertEquals("example.com", https.host());
        assertEquals(443, https.port());
        assertEquals("域名 · HTTPS", https.source());
        assertEquals(8443, overridden.port());
        assertEquals("::1", ipv6.host());
        assertEquals(9090, ipv6.port());
        assertEquals("IPv6", ipv6.source());
    }

    @Test
    void normalizesInternationalizedDomains() {
        var target = NetworkDiagnosticService.parseTarget("例子.测试", "443");

        assertEquals("xn--fsqu00a.xn--0zwm56d", target.host());
        assertEquals(443, target.port());
    }

    @Test
    void rejectsMissingOrInvalidPorts() {
        assertThrows(IllegalArgumentException.class,
                () -> NetworkDiagnosticService.parseTarget("example.com", "0"));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkDiagnosticService.parseTarget("example.com", "65536"));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkDiagnosticService.parseTarget("example.com:", ""));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkDiagnosticService.parseTarget("example.com:http", ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.inspect("example.com", "", 1_000,
                        NetworkDiagnosticService.DiagnosticMode.PORT_ONLY));
    }

    @Test
    void classifiesLocalAndPublicAddresses() throws Exception {
        assertEquals("回环地址", NetworkDiagnosticService.addressScope(
                InetAddress.getByName("127.0.0.1")));
        assertEquals("私有网络", NetworkDiagnosticService.addressScope(
                InetAddress.getByName("192.168.1.20")));
        assertEquals("公网 / 全局地址", NetworkDiagnosticService.addressScope(
                InetAddress.getByName("8.8.8.8")));
        assertEquals("运营商级 NAT", NetworkDiagnosticService.addressScope(
                InetAddress.getByName("100.64.0.1")));
        assertEquals("文档保留地址", NetworkDiagnosticService.addressScope(
                InetAddress.getByName("203.0.113.10")));
        assertEquals("文档保留地址", NetworkDiagnosticService.addressScope(
                InetAddress.getByName("2001:db8::1")));
    }

    @Test
    void resolvesIpWithoutExternalNetwork() throws Exception {
        var result = service.inspect("127.0.0.1", "", 1_000,
                NetworkDiagnosticService.DiagnosticMode.RESOLVE_ONLY);

        assertEquals("127.0.0.1", result.target().host());
        assertEquals(1, result.addresses().size());
        assertEquals("IPv4", result.addresses().getFirst().family());
        assertFalse(result.portCheck().checked());
        assertFalse(result.reachability().checked());
    }

    @Test
    void detectsOpenLoopbackTcpPort() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 8, loopback)) {
            var result = service.inspect(loopback.getHostAddress(),
                    String.valueOf(server.getLocalPort()), 1_000,
                    NetworkDiagnosticService.DiagnosticMode.PORT_ONLY);

            assertTrue(result.portCheck().checked());
            assertTrue(result.portCheck().open());
            assertEquals(server.getLocalPort(), result.portCheck().port());
        }
    }
}
