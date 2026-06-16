package plugin.javafxtools.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogTextTrimmerTest {
    @Test
    void returnsZeroWhenLineCountIsBelowLimit() {
        assertEquals(0, LogTextTrimmer.findTrimStartIndex(lines(12), 12, 800, 100));
    }

    @Test
    void removesAtLeastMinimumBatchWhenLimitIsExceeded() {
        String text = lines(805);

        assertEquals(indexAfterLines(text, 100),
                LogTextTrimmer.findTrimStartIndex(text, 805, 800, 100));
    }

    @Test
    void removesEnoughLinesWhenExcessIsLargerThanMinimumBatch() {
        String text = lines(1000);

        assertEquals(indexAfterLines(text, 201),
                LogTextTrimmer.findTrimStartIndex(text, 1000, 800, 100));
    }

    private static String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("line-").append(i).append('\n');
        }
        return text.toString();
    }

    private static int indexAfterLines(String text, int lineCount) {
        int index = 0;
        for (int i = 0; i < lineCount; i++) {
            index = text.indexOf('\n', index) + 1;
        }
        return index;
    }
}
