package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.model.LogMatchMode;
import plugin.javafxtools.model.LogMonitorConfig;
import plugin.javafxtools.model.LogMonitorMatch;
import plugin.javafxtools.model.LogMonitorRule;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogMonitorServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDirectory;

    @Test
    void waitsForMissingFileThenIgnoresItsHistoryAndMatchesLaterAppend() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        RecordingListener listener = new RecordingListener(0, 1, 0);
        try (LogMonitorService service = service()) {
            service.start(config(log, rule("error", "Error", "ERROR")), listener);
            assertTrue(listener.awaitStatusCount(1));
            Files.writeString(log, "history ERROR\n", StandardCharsets.UTF_8);
            assertTrue(listener.awaitStatusCount(2));
            append(log, "later ERROR\n");
            assertTrue(listener.awaitMatches());
            assertEquals(List.of(LogMonitorStatus.WAITING_FOR_FILE, LogMonitorStatus.RUNNING), listener.statuses());
            assertEquals(List.of("later ERROR"), listener.matches().stream().map(LogMonitorMatch::line).toList());
        }
    }

    @Test
    void publishesImmutableMatchesForEveryRuleMatchingOneCompleteLine() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "old ERROR\n", StandardCharsets.UTF_8);
        LogMonitorRule error = rule("error", "Error", "ERROR");
        LogMonitorRule timeout = rule("timeout", "Timeout", "timeout");
        RecordingListener listener = new RecordingListener(0, 1, 0);
        try (LogMonitorService service = service()) {
            service.start(config(log, error, timeout), listener);
            assertTrue(listener.awaitStatusCount(1));
            append(log, "ERROR timeout\n");
            assertTrue(listener.awaitMatches());
            List<LogMonitorMatch> matches = listener.matches();
            assertEquals(List.of("error", "timeout"), matches.stream().map(LogMonitorMatch::ruleId).toList());
            assertEquals(log, matches.getFirst().logFile());
            assertEquals("ERROR timeout", matches.getFirst().line());
            assertEquals(CLOCK.instant(), matches.getFirst().matchedAt());
            assertThrows(UnsupportedOperationException.class, () -> listener.lastBatch().add(matches.getFirst()));
        }
    }

    @Test
    void recoversAcrossTruncationAndReplacement() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "x".repeat(100) + "\n", StandardCharsets.UTF_8);
        RecordingListener listener = new RecordingListener(0, 2, 0);
        try (LogMonitorService service = service()) {
            service.start(config(log, rule("error", "Error", "ERROR")), listener);
            assertTrue(listener.awaitStatusCount(1));
            Files.writeString(log, "ERROR after truncate\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            assertTrue(listener.awaitMatchCount(1));
            Path replacement = tempDirectory.resolve("replacement.log");
            Files.writeString(replacement, "ERROR replacement\n", StandardCharsets.UTF_8);
            Files.move(replacement, log, StandardCopyOption.REPLACE_EXISTING);
            assertTrue(listener.awaitMatchCount(2));
            assertEquals(List.of("ERROR after truncate", "ERROR replacement"),
                    listener.matches().stream().map(LogMonitorMatch::line).toList());
        }
    }

    @Test
    void startAndStopAreIdempotent() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "old\n", StandardCharsets.UTF_8);
        RecordingListener listener = new RecordingListener(0, 1, 0);
        try (LogMonitorService service = service()) {
            LogMonitorConfig config = config(log, rule("error", "Error", "ERROR"));
            service.start(config, listener);
            service.start(config, listener);
            assertTrue(listener.awaitStatusCount(1));
            append(log, "ERROR once\n");
            assertTrue(listener.awaitMatches());
            service.stop();
            service.stop();
            assertTrue(listener.awaitStatusCount(2));
            assertFalse(service.isRunning());
            assertEquals(1, listener.matches().size());
            assertEquals(List.of(LogMonitorStatus.RUNNING, LogMonitorStatus.STOPPED), listener.statuses());
        }
    }

    @Test
    void suppressesDuplicateStatusAndReadErrorUntilRecovery() throws Exception {
        Path log = tempDirectory.resolve("folder");
        Files.createDirectory(log);
        RecordingListener listener = new RecordingListener(0, 0, 1);
        try (LogMonitorService service = service()) {
            service.start(config(log, rule("error", "Error", "ERROR")), listener);
            assertTrue(listener.awaitStatusCount(1));
            assertTrue(listener.awaitErrors());
            assertTrue(listener.awaitNoNewCallbacks(120));
            assertEquals(1, listener.statuses().size());
            assertEquals(1, listener.errors().size());
            Files.delete(log);
            assertTrue(listener.awaitStatusCount(2));
            Files.createDirectory(log);
            assertTrue(listener.awaitErrorCount(2));
            assertEquals(List.of(LogMonitorStatus.ERROR, LogMonitorStatus.WAITING_FOR_FILE, LogMonitorStatus.ERROR), listener.statuses());
            assertEquals(listener.errors().getFirst(), listener.errors().get(1));
        }
    }

    @Test
    void stopRestartAndCloseSuppressOldOrLaterCallbacks() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "old\n", StandardCharsets.UTF_8);
        RecordingListener first = new RecordingListener(0, 0, 0);
        RecordingListener second = new RecordingListener(0, 1, 0);
        LogMonitorService service = service();
        try {
            service.start(config(log, rule("error", "Error", "ERROR")), first);
            assertTrue(first.awaitStatusCount(1));
            assertTrue(Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().equals("log-monitor-poller"))
                    .allMatch(Thread::isDaemon));
            service.stop();
            service.start(config(log, rule("error", "Error", "ERROR")), second);
            assertTrue(second.awaitStatusCount(1));
            append(log, "ERROR current\n");
            assertTrue(second.awaitMatches());
            assertEquals(0, first.matches().size());
            service.close();
            int callbacks = second.callbackCount();
            append(log, "ERROR after close\n");
            assertTrue(second.awaitNoNewCallbacks(120));
            assertEquals(callbacks, second.callbackCount());
            service.close();
        } finally {
            service.close();
        }
    }

    private LogMonitorService service() { return new LogMonitorService(15, CLOCK); }

    private LogMonitorConfig config(Path path, LogMonitorRule... rules) {
        return new LogMonitorConfig(true, path.toString(), List.of(rules));
    }

    private LogMonitorRule rule(String id, String name, String expression) {
        return new LogMonitorRule(id, name, expression, LogMatchMode.CONTAINS, true, true);
    }

    private void append(Path log, String text) throws Exception {
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static final class RecordingListener implements LogMonitorService.Listener {
        private final List<LogMonitorStatus> statuses = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<LogMonitorMatch> matches = new ArrayList<>();
        private final List<List<LogMonitorMatch>> batches = new ArrayList<>();
        private final CountDownLatch statusLatch;
        private final CountDownLatch matchLatch;
        private final CountDownLatch errorLatch;

        private RecordingListener(int statuses, int matches, int errors) {
            statusLatch = new CountDownLatch(statuses);
            matchLatch = new CountDownLatch(matches);
            errorLatch = new CountDownLatch(errors);
        }

        @Override public synchronized void onStatusChanged(LogMonitorStatus status, String detail) { statuses.add(status); statusLatch.countDown(); notifyAll(); }
        @Override public synchronized void onMatches(List<LogMonitorMatch> values) { batches.add(values); matches.addAll(values); for (int i = 0; i < values.size(); i++) matchLatch.countDown(); notifyAll(); }
        @Override public synchronized void onReadError(String message) { errors.add(message); errorLatch.countDown(); notifyAll(); }
        synchronized List<LogMonitorStatus> statuses() { return List.copyOf(statuses); }
        synchronized List<String> errors() { return List.copyOf(errors); }
        synchronized List<LogMonitorMatch> matches() { return List.copyOf(matches); }
        synchronized List<LogMonitorMatch> lastBatch() { return batches.getLast(); }
        synchronized int callbackCount() { return statuses.size() + errors.size() + batches.size(); }
        boolean awaitStatuses() throws InterruptedException { return statusLatch.await(2, TimeUnit.SECONDS); }
        boolean awaitMatches() throws InterruptedException { return matchLatch.await(2, TimeUnit.SECONDS); }
        boolean awaitErrors() throws InterruptedException { return errorLatch.await(2, TimeUnit.SECONDS); }
        boolean awaitStatusCount(int count) throws InterruptedException { return awaitSize(() -> statuses().size(), count); }
        boolean awaitMatchCount(int count) throws InterruptedException { return awaitSize(() -> matches().size(), count); }
        boolean awaitErrorCount(int count) throws InterruptedException { return awaitSize(() -> errors().size(), count); }
        synchronized boolean awaitNoNewCallbacks(long millis) throws InterruptedException {
            int callbacks = callbackCount();
            wait(millis);
            return callbackCount() == callbacks;
        }
        private synchronized boolean awaitSize(java.util.function.IntSupplier size, int count) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < end) {
                if (size.getAsInt() >= count) return true;
                TimeUnit.NANOSECONDS.timedWait(this, end - System.nanoTime());
            }
            return size.getAsInt() >= count;
        }
    }
}
