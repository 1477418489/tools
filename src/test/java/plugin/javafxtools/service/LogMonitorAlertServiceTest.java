package plugin.javafxtools.service;

import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.LogMonitorMatch;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogMonitorAlertServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    void dispatchesOneShowToFxAndBeepsWhenEnabled() {
        Fixture fixture = new Fixture(true);
        try (LogMonitorAlertService service = fixture.service()) {
            service.accept(match("429", "line one", NOW));

            assertEquals(1, fixture.dispatcher.pendingCount());
            assertEquals(0, fixture.presenter.showCount);
            fixture.dispatcher.runAll();

            assertEquals(1, fixture.presenter.showCount);
            assertEquals(0, fixture.presenter.updateCount);
            assertEquals(1, fixture.beeps.get());
            assertEquals(1, fixture.presenter.snapshot.totalCount());
        }
    }

    @Test
    void updatesOpenDialogWithoutAnotherBeep() {
        Fixture fixture = new Fixture(true);
        try (LogMonitorAlertService service = fixture.service()) {
            service.accept(match("429", "first", NOW));
            fixture.dispatcher.runAll();
            fixture.clock.set(NOW.plusSeconds(1));

            service.accept(match("503", "second", NOW.plusSeconds(1)));
            fixture.dispatcher.runAll();

            assertEquals(1, fixture.presenter.showCount);
            assertEquals(1, fixture.presenter.updateCount);
            assertEquals(1, fixture.beeps.get());
            assertEquals(2, fixture.presenter.snapshot.totalCount());
        }
    }

    @Test
    void dispatchesOneFxTaskForABatchOfMatches() {
        Fixture fixture = new Fixture(true);
        try (LogMonitorAlertService service = fixture.service()) {
            service.acceptAll(List.of(
                    match("429", "first", NOW),
                    match("503", "second", NOW)));

            assertEquals(1, fixture.dispatcher.pendingCount());
            fixture.dispatcher.runAll();

            assertEquals(1, fixture.presenter.showCount);
            assertEquals(0, fixture.presenter.updateCount);
            assertEquals(1, fixture.beeps.get());
            assertEquals(2, fixture.presenter.snapshot.totalCount());
        }
    }

    @Test
    void highVolumeMatchesUseOneFxTaskAndPreserveEveryAlertCount() {
        Fixture fixture = new Fixture(true);
        try (LogMonitorAlertService service = fixture.service()) {
            for (int index = 1; index <= 1_000; index++) {
                service.accept(match("429", "line " + index, NOW.plusMillis(index)));
            }

            assertEquals(1, fixture.dispatcher.pendingCount());
            fixture.dispatcher.runAll();

            assertEquals(1, fixture.presenter.showCount);
            assertEquals(1_000, fixture.presenter.snapshot.totalCount());
            assertEquals(List.of("line 998", "line 999", "line 1000"),
                    fixture.presenter.snapshot.rules().getFirst().recentLines());
        }
    }

    @Test
    void resetInvalidatesQueuedMatchesAndClosesTheCurrentDialog() {
        Fixture fixture = new Fixture(true);
        try (LogMonitorAlertService service = fixture.service()) {
            service.accept(match("429", "stale", NOW));
            service.reset();
            service.accept(match("429", "fresh", NOW.plusSeconds(1)));

            fixture.dispatcher.runAll();
            assertEquals(1, fixture.presenter.showCount);
            assertEquals(List.of("fresh"),
                    fixture.presenter.snapshot.rules().getFirst().recentLines());

            service.reset();
            fixture.dispatcher.runAll();
            assertEquals(1, fixture.presenter.closeCount);
        }
    }

    @Test
    void resetCancelsCooldownAndPreventsAStoppedSessionFromReopening() {
        Fixture fixture = new Fixture(true);
        try (LogMonitorAlertService service = fixture.service()) {
            service.accept(match("429", "shown", NOW));
            fixture.dispatcher.runAll();
            fixture.clock.set(NOW.plusSeconds(2));
            fixture.presenter.hide();
            fixture.clock.set(NOW.plusSeconds(10));
            service.accept(match("429", "pending", NOW.plusSeconds(10)));
            fixture.dispatcher.runAll();

            FakeTask stoppedTask = fixture.scheduler.current;
            service.reset();
            fixture.dispatcher.runAll();
            fixture.scheduler.fireCurrent();

            assertTrue(stoppedTask.cancelled);
            assertEquals(0, fixture.dispatcher.pendingCount());
            assertEquals(1, fixture.presenter.showCount);
        }
    }

    @Test
    void formattedAlertTextHasGlobalRuleAndCharacterBounds() {
        List<LogAlertAccumulator.RuleSummary> rules = new ArrayList<>();
        String oversizedLine = "x".repeat(10_000);
        for (int index = 0; index < 100; index++) {
            rules.add(new LogAlertAccumulator.RuleSummary(
                    "rule-" + index, "Rule " + index, "ERROR", 10,
                    NOW, NOW.plusSeconds(1),
                    List.of(oversizedLine, oversizedLine, oversizedLine)));
        }

        String formatted = LogMonitorAlertService.format(
                new LogAlertAccumulator.Snapshot(rules));

        assertTrue(formatted.length() < 101_000);
        assertTrue(formatted.contains("规则详情已省略"));
        assertFalse(formatted.contains(oversizedLine));
    }

    @Test
    void rejectedFxDispatchCanBeRetriedWithoutLosingCompressedMatches() {
        Fixture fixture = new Fixture(true);
        try (LogMonitorAlertService service = fixture.service()) {
            fixture.dispatcher.rejectNext();
            service.accept(match("429", "first", NOW));
            service.accept(match("429", "second", NOW.plusSeconds(1)));

            assertEquals(1, fixture.dispatcher.pendingCount());
            fixture.dispatcher.runAll();

            assertEquals(2, fixture.presenter.snapshot.totalCount());
            assertEquals(List.of("first", "second"),
                    fixture.presenter.snapshot.rules().getFirst().recentLines());
        }
    }

    @Test
    void closeOnFxThreadClosesDialogImmediately() {
        Fixture fixture = new Fixture(true);
        LogMonitorAlertService service = fixture.serviceOnFxThread();
        service.accept(match("429", "shown", NOW));
        fixture.dispatcher.runAll();

        service.close();

        assertEquals(1, fixture.presenter.closeCount);
        assertEquals(0, fixture.dispatcher.pendingCount());
    }

    @Test
    void schedulesOneCooldownWakeupAndShowsThePendingSummary() {
        Fixture fixture = new Fixture(true);
        try (LogMonitorAlertService service = fixture.service()) {
            service.accept(match("429", "shown", NOW));
            fixture.dispatcher.runAll();
            fixture.clock.set(NOW.plusSeconds(2));
            fixture.presenter.hide();

            fixture.clock.set(NOW.plusSeconds(10));
            service.accept(match("429", "pending one", NOW.plusSeconds(10)));
            fixture.dispatcher.runAll();
            fixture.clock.set(NOW.plusSeconds(11));
            service.accept(match("429", "pending two", NOW.plusSeconds(11)));
            fixture.dispatcher.runAll();

            assertEquals(1, fixture.scheduler.scheduleCount);
            assertEquals(Duration.ofSeconds(52), fixture.scheduler.lastDelay);
            assertEquals(1, fixture.presenter.showCount);

            fixture.clock.set(NOW.plusSeconds(62));
            fixture.scheduler.fireCurrent();
            assertEquals(1, fixture.dispatcher.pendingCount());
            fixture.dispatcher.runAll();

            assertEquals(2, fixture.presenter.showCount);
            assertEquals(2, fixture.presenter.snapshot.totalCount());
            assertEquals(List.of("pending one", "pending two"),
                    fixture.presenter.snapshot.rules().getFirst().recentLines());
            assertEquals(2, fixture.beeps.get());
        }
    }

    @Test
    void disabledSoundSupplierSuppressesBeep() {
        Fixture fixture = new Fixture(true);
        try (LogMonitorAlertService service = fixture.service()) {
            service.setSoundEnabledSupplier(() -> false);
            service.accept(match("429", "quiet", NOW));
            fixture.dispatcher.runAll();

            assertEquals(1, fixture.presenter.showCount);
            assertEquals(0, fixture.beeps.get());
        }
    }

    @Test
    void closeCancelsWakeupClosesDialogAndRejectsLaterWork() {
        Fixture fixture = new Fixture(true);
        LogMonitorAlertService service = fixture.service();
        service.accept(match("429", "shown", NOW));
        fixture.dispatcher.runAll();
        fixture.clock.set(NOW.plusSeconds(2));
        fixture.presenter.hide();
        fixture.clock.set(NOW.plusSeconds(10));
        service.accept(match("429", "pending", NOW.plusSeconds(10)));
        fixture.dispatcher.runAll();
        service.accept(match("503", "open", NOW.plusSeconds(10)));
        fixture.dispatcher.runAll();

        service.close();
        assertTrue(fixture.scheduler.closed);
        assertTrue(fixture.scheduler.current.cancelled);
        fixture.dispatcher.runAll();
        assertEquals(1, fixture.presenter.closeCount);

        int dispatches = fixture.dispatcher.totalDispatches;
        service.accept(match("503", "after close", NOW.plusSeconds(11)));
        fixture.scheduler.fireCurrent();
        assertEquals(dispatches, fixture.dispatcher.totalDispatches);
        service.close();
    }

    private LogMonitorMatch match(String ruleId, String line, Instant matchedAt) {
        return new LogMonitorMatch(ruleId, "HTTP " + ruleId, ruleId,
                Path.of("application.log"), line, matchedAt);
    }

    private static final class Fixture {
        private final RecordingDispatcher dispatcher = new RecordingDispatcher();
        private final MutableClock clock = new MutableClock(NOW);
        private final FakePresenter presenter = new FakePresenter();
        private final FakeScheduler scheduler = new FakeScheduler();
        private final AtomicInteger beeps = new AtomicInteger();
        private final boolean soundEnabled;

        private Fixture(boolean soundEnabled) {
            this.soundEnabled = soundEnabled;
        }

        private LogMonitorAlertService service() {
            return new LogMonitorAlertService(dispatcher, clock, () -> soundEnabled,
                    presenter, scheduler, beeps::incrementAndGet);
        }

        private LogMonitorAlertService serviceOnFxThread() {
            return new LogMonitorAlertService(dispatcher, () -> true,
                    clock, () -> soundEnabled,
                    presenter, scheduler, beeps::incrementAndGet);
        }
    }

    private static final class RecordingDispatcher implements LogMonitorAlertService.FxDispatcher {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private int totalDispatches;
        private boolean rejectNext;

        @Override
        public void dispatch(Runnable task) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("JavaFX unavailable");
            }
            tasks.addLast(task);
            totalDispatches++;
        }

        private void rejectNext() {
            rejectNext = true;
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }

        private int pendingCount() {
            return tasks.size();
        }
    }

    private static final class FakePresenter implements LogMonitorAlertService.DialogPresenter {
        private int showCount;
        private int updateCount;
        private int closeCount;
        private LogAlertAccumulator.Snapshot snapshot;
        private Runnable hiddenAction;

        @Override
        public LogMonitorAlertService.DialogHandle show(
                Stage owner, LogAlertAccumulator.Snapshot value, Runnable onHidden) {
            showCount++;
            snapshot = value;
            hiddenAction = onHidden;
            return new LogMonitorAlertService.DialogHandle() {
                @Override
                public void update(LogAlertAccumulator.Snapshot updated) {
                    updateCount++;
                    snapshot = updated;
                }

                @Override
                public void close() {
                    closeCount++;
                }
            };
        }

        private void hide() {
            Runnable action = hiddenAction;
            hiddenAction = null;
            action.run();
        }
    }

    private static final class FakeScheduler implements LogMonitorAlertService.WakeUpScheduler {
        private final List<FakeTask> tasks = new ArrayList<>();
        private int scheduleCount;
        private Duration lastDelay;
        private FakeTask current;
        private boolean closed;

        @Override
        public LogMonitorAlertService.ScheduledTask schedule(Runnable action, Duration delay) {
            scheduleCount++;
            lastDelay = delay;
            current = new FakeTask(action);
            tasks.add(current);
            return current;
        }

        @Override
        public void close() {
            closed = true;
        }

        private void fireCurrent() {
            if (current != null && !current.cancelled) {
                current.action.run();
            }
        }
    }

    private static final class FakeTask implements LogMonitorAlertService.ScheduledTask {
        private final Runnable action;
        private boolean cancelled;

        private FakeTask(Runnable action) {
            this.action = action;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
