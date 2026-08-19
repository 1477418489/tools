package plugin.javafxtools.controller;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.LogMonitorMatch;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogMonitorFxDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    @Test
    void highVolumeMatchesUseOneBoundedBatchedFxPipeline() {
        Deque<Runnable> fxTasks = new ArrayDeque<>();
        List<LogMonitorMatch> delivered = new ArrayList<>();
        List<Long> dropped = new ArrayList<>();
        LogMonitorFxDispatcher dispatcher = new LogMonitorFxDispatcher(
                fxTasks::addLast,
                (matches, droppedCount) -> {
                    delivered.addAll(matches);
                    dropped.add(droppedCount);
                },
                5, 10_000, 2, 10_000);

        for (int index = 0; index < 10; index++) {
            dispatcher.submit(List.of(match(index, "line-" + index)));
        }

        assertEquals(1, fxTasks.size());
        assertEquals(3, drainTasks(fxTasks));
        assertEquals(List.of("line-5", "line-6", "line-7", "line-8", "line-9"),
                delivered.stream().map(LogMonitorMatch::line).toList());
        assertEquals(5L, dropped.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void pendingCharacterLimitRetainsTheNewestMatches() {
        Deque<Runnable> fxTasks = new ArrayDeque<>();
        List<LogMonitorMatch> delivered = new ArrayList<>();
        List<LogMonitorMatch> matches = List.of(
                match(1, "a".repeat(80)),
                match(2, "b".repeat(80)),
                match(3, "c".repeat(80)));
        LogMonitorFxDispatcher dispatcher = new LogMonitorFxDispatcher(
                fxTasks::addLast,
                (batch, droppedCount) -> delivered.addAll(batch),
                10, 180, 10, 90);

        dispatcher.submit(matches);
        drainTasks(fxTasks);

        assertEquals(List.of(matches.getLast()), delivered);
    }

    @Test
    void clearInvalidatesQueuedWorkAndAllowsLaterMatches() {
        Deque<Runnable> fxTasks = new ArrayDeque<>();
        List<String> delivered = new ArrayList<>();
        LogMonitorFxDispatcher dispatcher = new LogMonitorFxDispatcher(
                fxTasks::addLast,
                (matches, droppedCount) -> matches.stream()
                        .map(LogMonitorMatch::line).forEach(delivered::add),
                10, 10_000, 10, 10_000);

        dispatcher.submit(List.of(match(1, "old")));
        dispatcher.clear();
        dispatcher.submit(List.of(match(2, "new")));
        drainTasks(fxTasks);

        assertEquals(List.of("new"), delivered);
    }

    @Test
    void closeDropsQueuedAndFutureWork() {
        Deque<Runnable> fxTasks = new ArrayDeque<>();
        List<LogMonitorMatch> delivered = new ArrayList<>();
        LogMonitorFxDispatcher dispatcher = new LogMonitorFxDispatcher(
                fxTasks::addLast,
                (matches, droppedCount) -> delivered.addAll(matches));

        dispatcher.submit(List.of(match(1, "queued")));
        dispatcher.close();
        dispatcher.submit(List.of(match(2, "later")));
        drainTasks(fxTasks);

        assertEquals(List.of(), delivered);
    }

    private static LogMonitorMatch match(int index, String line) {
        return new LogMonitorMatch("rule-" + index, "Rule " + index, "ERROR",
                Path.of("application.log"), line, NOW.plusSeconds(index));
    }

    private static int drainTasks(Deque<Runnable> tasks) {
        int count = 0;
        while (!tasks.isEmpty()) {
            tasks.removeFirst().run();
            count++;
        }
        return count;
    }
}
