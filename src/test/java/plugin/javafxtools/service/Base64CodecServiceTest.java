package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.service.Base64CodecService.TextEncoding;
import plugin.javafxtools.service.Base64CodecService.Variant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base64CodecServiceTest {
    private final Base64CodecService service = new Base64CodecService();

    @Test
    void roundTripsUtf8TextWithStandardAlphabet() {
        String encoded = service.encode("FxTools 中文", Variant.STANDARD, TextEncoding.UTF_8);

        assertEquals("FxTools 中文",
                service.decode(encoded, Variant.STANDARD, TextEncoding.UTF_8));
    }

    @Test
    void urlSafeEncodingOmitsPadding() {
        String encoded = service.encode("a", Variant.URL_SAFE, TextEncoding.UTF_8);

        assertEquals("YQ", encoded);
        assertFalse(encoded.contains("="));
        assertEquals("a", service.decode(encoded, Variant.URL_SAFE, TextEncoding.UTF_8));
    }

    @Test
    void standardDecoderAcceptsWhitespaceBetweenBase64Chunks() {
        assertEquals("hello", service.decode("aG Vs\nbG8=",
                Variant.STANDARD, TextEncoding.UTF_8));
    }

    @Test
    void rejectsMalformedBase64AndBytesThatDoNotMatchSelectedEncoding() {
        assertThrows(IllegalArgumentException.class,
                () -> service.decode("not-base64!", Variant.STANDARD, TextEncoding.UTF_8));
        assertThrows(IllegalArgumentException.class,
                () -> service.decode("/w==", Variant.STANDARD, TextEncoding.UTF_8));
        assertThrows(IllegalArgumentException.class,
                () -> service.decode("aG!VsbG8=", Variant.MIME, TextEncoding.UTF_8));
    }

    @Test
    void rejectsInputBeyondTheBoundedWorkspaceLimit() {
        String oversized = "a".repeat(Base64CodecService.MAX_INPUT_CHARACTERS + 1);

        assertThrows(IllegalArgumentException.class,
                () -> service.encode(oversized, Variant.STANDARD, TextEncoding.UTF_8));
    }
}
