package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpResponseFormatterTest {
    @Test
    void prettyJsonPreservesArbitraryPrecisionNumbers() {
        String number = "0.123456789012345678901234567890";
        String formatted = new HttpResponseFormatter()
                .tryPrettyJson("{\"value\":" + number + "}");

        assertNotNull(formatted);
        assertTrue(formatted.contains(number));
    }
}
