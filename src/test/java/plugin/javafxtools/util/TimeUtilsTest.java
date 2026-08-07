package plugin.javafxtools.util;

import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeUtilsTest {
    @Test
    void formatsCurrentTimeWithTheDefaultPattern() {
        assertTrue(TimeUtils.getCurrentDateTime()
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void defaultDateFormattingPreservesTheLegacyResult() {
        Date value = new Date(1_735_689_600_000L);
        String expected = new SimpleDateFormat(TimeUtils.DEFAULT_DATETIME_FORMAT).format(value);

        assertEquals(expected, TimeUtils.formatDateTime(value));
        assertEquals("", TimeUtils.formatDateTime(null));
    }
}
