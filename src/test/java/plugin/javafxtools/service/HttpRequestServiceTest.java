package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpRequestServiceTest {
    @Test
    void errorResponseWithoutBodyReturnsEmptyString() throws Exception {
        HttpURLConnection connection = new StubHttpURLConnection(null);
        HttpRequestService service = new HttpRequestService();

        assertEquals("", service.readResponseBody(connection, 404));
    }

    @Test
    void responseBodyPreservesWhitespaceAndLineEndings() throws Exception {
        String body = "  first line\r\nsecond line \n";
        HttpURLConnection connection = new StubHttpURLConnection(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        HttpRequestService service = new HttpRequestService();

        assertEquals(body, service.readResponseBody(connection, 404));
    }

    @Test
    void responseBodyUsesDeclaredCharset() throws Exception {
        String body = "caf\u00e9";
        HttpURLConnection connection = new StubHttpURLConnection(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)),
                "text/plain; charset=ISO-8859-1");
        HttpRequestService service = new HttpRequestService();

        assertEquals(body, service.readResponseBody(connection, 404));
    }

    @Test
    void invalidRequestUrlIsReportedAsIoError() {
        HttpRequestService service = new HttpRequestService();

        assertThrows(java.io.IOException.class,
                () -> service.sendRequest("http://bad host", "GET", "", "", 100, 100));
    }

    @Test
    void nonHttpRequestUrlIsRejectedBeforeOpeningAConnection() {
        HttpRequestService service = new HttpRequestService();

        assertThrows(java.io.IOException.class,
                () -> service.sendRequest("file:///tmp/config.json", "GET", "", "", 100, 100));
    }

    @Test
    void getParametersAreEncodedBeforeTheUrlFragment() {
        HttpRequestService service = new HttpRequestService();

        assertEquals("https://example.com/api?q=hello+world&health+check#details",
                service.buildRequestUrl(
                        "https://example.com/api#details", "GET", "q=hello world&health check"));
    }

    @Test
    void blankGetParametersDoNotChangeTheUrl() {
        HttpRequestService service = new HttpRequestService();

        assertEquals("https://example.com/api#details",
                service.buildRequestUrl("https://example.com/api#details", "GET", "  \t"));
    }

    @Test
    void malformedHeaderLineIsRejected() {
        HttpRequestService service = new HttpRequestService();

        assertThrows(java.io.IOException.class,
                () -> service.parseHeaders("Accept: application/json\ninvalid header"));
    }

    private static final class StubHttpURLConnection extends HttpURLConnection {
        private final InputStream errorStream;
        private final String contentType;

        private StubHttpURLConnection(InputStream errorStream) throws Exception {
            this(errorStream, null);
        }

        private StubHttpURLConnection(InputStream errorStream, String contentType) throws Exception {
            super(URI.create("http://localhost").toURL());
            this.errorStream = errorStream;
            this.contentType = contentType;
        }

        @Override
        public InputStream getErrorStream() {
            return errorStream;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
