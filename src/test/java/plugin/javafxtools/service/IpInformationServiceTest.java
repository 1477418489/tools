package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpInformationServiceTest {
    private static final String SUCCESS_RESPONSE = """
            {
              "success": true,
              "ip": "8.8.8.8",
              "type": "IPv4",
              "continent": "North America",
              "country": "United States",
              "region": "California",
              "city": "Mountain View",
              "postal": "94043",
              "latitude": 37.4056,
              "longitude": -122.0775,
              "connection": {
                "asn": 15169,
                "org": "Google LLC",
                "isp": "Google LLC",
                "domain": "google.com"
              },
              "timezone": {
                "id": "America/Los_Angeles",
                "utc": "-07:00"
              }
            }
            """;

    @Test
    void parsesPublicIpInformation() throws Exception {
        var information = IpInformationService.parseResponse("8.8.8.8", SUCCESS_RESPONSE);

        assertEquals("8.8.8.8", information.ip());
        assertEquals("IPv4", information.type());
        assertEquals("United States / California / Mountain View / 94043",
                information.location());
        assertEquals("Google LLC / AS15169 / google.com", information.network());
        assertEquals("America/Los_Angeles / -07:00", information.timeZoneDisplay());
        assertEquals("37.4056, -122.0775", information.coordinates());
        assertFalse(information.local());
    }

    @Test
    void rejectsProviderFailureResponse() {
        IOException error = assertThrows(IOException.class,
                () -> IpInformationService.parseResponse("bad", """
                        {"success":false,"message":"Invalid IP address"}
                        """));

        assertEquals("Invalid IP address", error.getMessage());
    }

    @Test
    void keepsPrivateAddressLookupLocal() throws Exception {
        var information = new IpInformationService().lookupTarget("127.0.0.1", 1_000);

        assertTrue(information.local());
        assertEquals("127.0.0.1", information.ip());
        assertEquals("回环地址", information.scope());
        assertEquals("本地分析", information.dataSource());
    }

    @Test
    void doesNotSendReservedAddressToPublicProvider() throws Exception {
        var information = new IpInformationService().lookupTarget("203.0.113.10", 1_000);

        assertTrue(information.local());
        assertEquals("文档保留地址", information.scope());
        assertEquals("本地分析", information.dataSource());
    }
}
