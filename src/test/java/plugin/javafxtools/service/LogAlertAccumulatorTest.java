package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.LogMonitorMatch;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogAlertAccumulatorTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    void firstMatchShowsAnImmutableSnapshot() {
        LogAlertAccumulator accumulator = accumulator();
        LogMonitorMatch match = match("429", "HTTP 429", "line one", NOW.minusSeconds(1));

        LogAlertAccumulator.Change change = accumulator.onMatch(match, NOW);

        assertEquals(LogAlertAccumulator.Action.SHOW, change.action());
        assertEquals(Optional.empty(), change.nextWakeUp());
        assertEquals(1, change.snapshot().totalCount());
        assertEquals(List.of(new LogAlertAccumulator.RuleSummary(
                "429", "HTTP 429", "429", 1,
                NOW.minusSeconds(1), NOW.minusSeconds(1), List.of("line one"))),
                change.snapshot().rules());
        assertThrows(UnsupportedOperationException.class,
                () -> change.snapshot().rules().add(change.snapshot().rules().getFirst()));
        assertThrows(UnsupportedOperationException.class,
                () -> change.snapshot().rules().getFirst().recentLines().add("mutate"));
    }

    @Test
    void openDialogUpdatesAndMergesRules() {
        LogAlertAccumulator accumulator = accumulator();
        accumulator.onMatch(match("429", "HTTP 429", "rate limited", NOW), NOW);

        LogAlertAccumulator.Change second = accumulator.onMatch(
                match("503", "HTTP 503", "service unavailable", NOW.plusSeconds(1)),
                NOW.plusSeconds(1));
        LogAlertAccumulator.Change third = accumulator.onMatch(
                match("429", "HTTP 429", "rate limited again", NOW.plusSeconds(2)),
                NOW.plusSeconds(2));

        assertEquals(LogAlertAccumulator.Action.UPDATE, second.action());
        assertEquals(LogAlertAccumulator.Action.UPDATE, third.action());
        assertEquals(3, third.snapshot().totalCount());
        assertEquals(List.of("429", "503"),
                third.snapshot().rules().stream().map(LogAlertAccumulator.RuleSummary::ruleId).toList());
        assertEquals(2, third.snapshot().rules().getFirst().count());
        assertEquals(List.of("rate limited", "rate limited again"),
                third.snapshot().rules().getFirst().recentLines());
    }

    @Test
    void closedDialogStartsCooldownAndShowsPendingSummaryAtExpiry() {
        LogAlertAccumulator accumulator = accumulator();
        accumulator.onMatch(match("429", "HTTP 429", "first", NOW), NOW);
        accumulator.onDialogClosed(NOW.plusSeconds(2));

        LogAlertAccumulator.Change pending = accumulator.onMatch(
                match("429", "HTTP 429", "during cooldown", NOW.plusSeconds(10)),
                NOW.plusSeconds(10));
        LogAlertAccumulator.Change early = accumulator.cooldownElapsed(NOW.plusSeconds(61));
        LogAlertAccumulator.Change expired = accumulator.cooldownElapsed(NOW.plusSeconds(62));

        assertEquals(LogAlertAccumulator.Action.NONE, pending.action());
        assertEquals(Optional.of(NOW.plusSeconds(62)), pending.nextWakeUp());
        assertEquals(List.of(), pending.snapshot().rules());
        assertEquals(LogAlertAccumulator.Action.NONE, early.action());
        assertEquals(Optional.of(NOW.plusSeconds(62)), early.nextWakeUp());
        assertEquals(LogAlertAccumulator.Action.SHOW, expired.action());
        assertEquals(1, expired.snapshot().totalCount());
        assertEquals(List.of("during cooldown"), expired.snapshot().rules().getFirst().recentLines());
        assertEquals(Optional.empty(), expired.nextWakeUp());
    }

    @Test
    void cooldownIsPerRuleAndDuePendingRulesUpdateAnOpenDialog() {
        LogAlertAccumulator accumulator = accumulator();
        accumulator.onMatch(match("429", "HTTP 429", "first 429", NOW), NOW);
        accumulator.onDialogClosed(NOW.plusSeconds(2));
        accumulator.onMatch(match("429", "HTTP 429", "pending 429", NOW.plusSeconds(10)),
                NOW.plusSeconds(10));

        LogAlertAccumulator.Change show503 = accumulator.onMatch(
                match("503", "HTTP 503", "fresh 503", NOW.plusSeconds(11)),
                NOW.plusSeconds(11));
        LogAlertAccumulator.Change merge429 = accumulator.cooldownElapsed(NOW.plusSeconds(62));

        assertEquals(LogAlertAccumulator.Action.SHOW, show503.action());
        assertEquals(List.of("503"),
                show503.snapshot().rules().stream().map(LogAlertAccumulator.RuleSummary::ruleId).toList());
        assertEquals(Optional.of(NOW.plusSeconds(62)), show503.nextWakeUp());
        assertEquals(LogAlertAccumulator.Action.UPDATE, merge429.action());
        assertEquals(List.of("503", "429"),
                merge429.snapshot().rules().stream().map(LogAlertAccumulator.RuleSummary::ruleId).toList());
    }

    @Test
    void anotherOpenRuleDoesNotBypassPerRuleCooldown() {
        LogAlertAccumulator accumulator = accumulator();
        accumulator.onMatch(match("429", "HTTP 429", "first 429", NOW), NOW);
        accumulator.onDialogClosed(NOW.plusSeconds(2));
        accumulator.onMatch(match("503", "HTTP 503", "fresh 503", NOW.plusSeconds(10)),
                NOW.plusSeconds(10));

        LogAlertAccumulator.Change pending429 = accumulator.onMatch(
                match("429", "HTTP 429", "cooling 429", NOW.plusSeconds(11)),
                NOW.plusSeconds(11));
        LogAlertAccumulator.Change due429 = accumulator.cooldownElapsed(NOW.plusSeconds(62));

        assertEquals(LogAlertAccumulator.Action.NONE, pending429.action());
        assertEquals(List.of("503"), pending429.snapshot().rules().stream()
                .map(LogAlertAccumulator.RuleSummary::ruleId).toList());
        assertEquals(Optional.of(NOW.plusSeconds(62)), pending429.nextWakeUp());
        assertEquals(LogAlertAccumulator.Action.UPDATE, due429.action());
        assertEquals(List.of("503", "429"), due429.snapshot().rules().stream()
                .map(LogAlertAccumulator.RuleSummary::ruleId).toList());
    }

    @Test
    void batchProducesOneShowActionWithAllMatches() {
        LogAlertAccumulator accumulator = accumulator();

        LogAlertAccumulator.Change change = accumulator.onMatches(List.of(
                match("429", "HTTP 429", "first", NOW),
                match("503", "HTTP 503", "second", NOW)), NOW);

        assertEquals(LogAlertAccumulator.Action.SHOW, change.action());
        assertEquals(2, change.snapshot().totalCount());
    }

    @Test
    void compressedSummariesPreserveCountsWithoutReplayingEveryMatch() {
        LogAlertAccumulator accumulator = accumulator();

        LogAlertAccumulator.Change first = accumulator.onSummaries(List.of(
                new LogAlertAccumulator.MatchSummary(
                        "429", "HTTP 429", "429", 1_000,
                        NOW, NOW.plusSeconds(10), List.of("line 998", "line 999", "line 1000"))),
                NOW.plusSeconds(10));
        LogAlertAccumulator.Change second = accumulator.onSummaries(List.of(
                new LogAlertAccumulator.MatchSummary(
                        "429", "HTTP 429", "429", 250,
                        NOW.plusSeconds(11), NOW.plusSeconds(20),
                        List.of("line 1249", "line 1250"))),
                NOW.plusSeconds(20));

        assertEquals(LogAlertAccumulator.Action.SHOW, first.action());
        assertEquals(LogAlertAccumulator.Action.UPDATE, second.action());
        assertEquals(1_250, second.snapshot().totalCount());
        assertEquals(List.of("line 1000", "line 1249", "line 1250"),
                second.snapshot().rules().getFirst().recentLines());
    }

    @Test
    void resetClearsActivePendingAndCooldownState() {
        LogAlertAccumulator accumulator = accumulator();
        accumulator.onMatch(match("429", "HTTP 429", "shown", NOW), NOW);
        accumulator.onDialogClosed(NOW.plusSeconds(1));
        accumulator.onMatch(match("429", "HTTP 429", "pending", NOW.plusSeconds(2)),
                NOW.plusSeconds(2));

        accumulator.reset();
        LogAlertAccumulator.Change elapsed = accumulator.cooldownElapsed(NOW.plusSeconds(120));
        LogAlertAccumulator.Change fresh = accumulator.onMatch(
                match("429", "HTTP 429", "fresh", NOW.plusSeconds(121)),
                NOW.plusSeconds(121));

        assertEquals(LogAlertAccumulator.Action.NONE, elapsed.action());
        assertEquals(0, elapsed.snapshot().totalCount());
        assertEquals(LogAlertAccumulator.Action.SHOW, fresh.action());
        assertEquals(1, fresh.snapshot().totalCount());
        assertEquals(List.of("fresh"), fresh.snapshot().rules().getFirst().recentLines());
    }

    @Test
    void duePendingRulesAreMergedIntoOnePopup() {
        LogAlertAccumulator accumulator = accumulator();
        accumulator.onMatch(match("429", "HTTP 429", "first 429", NOW), NOW);
        accumulator.onMatch(match("503", "HTTP 503", "first 503", NOW.plusSeconds(1)),
                NOW.plusSeconds(1));
        accumulator.onDialogClosed(NOW.plusSeconds(2));
        accumulator.onMatch(match("429", "HTTP 429", "pending 429", NOW.plusSeconds(10)),
                NOW.plusSeconds(10));
        accumulator.onMatch(match("503", "HTTP 503", "pending 503", NOW.plusSeconds(11)),
                NOW.plusSeconds(11));

        LogAlertAccumulator.Change change = accumulator.cooldownElapsed(NOW.plusSeconds(62));

        assertEquals(LogAlertAccumulator.Action.SHOW, change.action());
        assertEquals(2, change.snapshot().totalCount());
        assertEquals(List.of("429", "503"),
                change.snapshot().rules().stream().map(LogAlertAccumulator.RuleSummary::ruleId).toList());
    }

    @Test
    void retainsOnlyTheNewestConfiguredLinesPerRule() {
        LogAlertAccumulator accumulator = accumulator();

        for (int index = 1; index <= 5; index++) {
            accumulator.onMatch(match("429", "HTTP 429", "line " + index, NOW.plusSeconds(index)),
                    NOW.plusSeconds(index));
        }

        LogAlertAccumulator.RuleSummary summary = accumulator.cooldownElapsed(NOW.plusSeconds(6))
                .snapshot().rules().getFirst();
        assertEquals(5, summary.count());
        assertEquals(NOW.plusSeconds(1), summary.firstMatchedAt());
        assertEquals(NOW.plusSeconds(5), summary.lastMatchedAt());
        assertEquals(List.of("line 3", "line 4", "line 5"), summary.recentLines());
    }

    @Test
    void rejectsInvalidConstructionArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new LogAlertAccumulator(Duration.ZERO, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new LogAlertAccumulator(Duration.ofSeconds(60), 0));
    }

    private LogAlertAccumulator accumulator() {
        return new LogAlertAccumulator(Duration.ofSeconds(60), 3);
    }

    private LogMonitorMatch match(String ruleId, String ruleName, String line, Instant matchedAt) {
        return new LogMonitorMatch(ruleId, ruleName, ruleId, Path.of("application.log"), line, matchedAt);
    }
}
