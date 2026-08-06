package plugin.javafxtools.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUrlSupportTest {
    @Test
    void acceptsHttpAndHttpsUrlsWithHosts() {
        assertEquals("example.com", HttpUrlSupport.parse("https://example.com/path").getHost());
        assertTrue(HttpUrlSupport.isValid("http://localhost:8080"));
    }

    @Test
    void rejectsUnsupportedOrHostlessUrls() {
        assertFalse(HttpUrlSupport.isValid("file:///tmp/config.json"));
        assertFalse(HttpUrlSupport.isValid("https://"));
        assertFalse(HttpUrlSupport.isValid("example.com"));
    }
}
