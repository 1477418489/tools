package plugin.javafxtools.control;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class LogSearchSupportTest {
    @Test
    void countsCaseInsensitiveOverlappingMatches() {
        assertEquals(2, LogSearchSupport.countMatches("Error error", "ERROR"));
        assertEquals(2, LogSearchSupport.countMatches("aaa", "aa"));
    }

    @Test
    void nextSearchWrapsToTheFirstMatch() {
        String text = "alpha beta alpha";

        assertEquals(0, LogSearchSupport.findNext(text, "alpha", text.length()));
        assertEquals(11, LogSearchSupport.findNext(text, "alpha", 1));
    }

    @Test
    void previousSearchWrapsToTheLastMatch() {
        String text = "alpha beta alpha";

        assertEquals(11, LogSearchSupport.findPrevious(text, "alpha", -1));
        assertEquals(0, LogSearchSupport.findPrevious(text, "alpha", 10));
    }

    @Test
    void calculatesTheSelectedMatchOrdinal() {
        assertEquals(2, LogSearchSupport.matchOrdinal("one ONE one", "one", 4));
        assertEquals(0, LogSearchSupport.matchOrdinal("one", "", 0));
    }

    @Test
    void returnsNavigationStatisticsInOneScan() {
        LogSearchSupport.SearchResult next =
                LogSearchSupport.findNextWithStats("alpha ALPHA alpha", "alpha", 1);
        LogSearchSupport.SearchResult previous =
                LogSearchSupport.findPreviousWithStats("alpha ALPHA alpha", "alpha", 10);

        assertEquals(new LogSearchSupport.SearchResult(6, 2, 3), next);
        assertEquals(new LogSearchSupport.SearchResult(6, 2, 3), previous);
    }

    @Test
    void summarizesOverlappingMatchesAndSelectedOrdinal() {
        assertEquals(
                new LogSearchSupport.SearchSummary(3, 0, 2),
                LogSearchSupport.summarize("aaaa", "aa", 1));
    }

    @Test
    void scansLargeRepetitiveLogsWithoutQuadraticSlowdown() {
        String text = "a".repeat(1_000_000);
        String query = "a".repeat(255) + "b";

        assertTimeout(Duration.ofSeconds(1),
                () -> assertEquals(0, LogSearchSupport.countMatches(text, query)));
    }
}
