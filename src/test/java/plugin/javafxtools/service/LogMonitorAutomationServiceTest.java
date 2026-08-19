package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.LogMonitorAutomation;
import plugin.javafxtools.model.LogMonitorMatch;
import plugin.javafxtools.model.LogRemoteMatchAction;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogMonitorAutomationServiceTest {
    @Test
    void countGateHonorsStartIntervalAndMaximum() throws Exception {
        List<String> sentTexts = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch sent = new CountDownLatch(2);
        try (LogMonitorAutomationService service = new LogMonitorAutomationService(
                url -> new LogMonitorAutomationService.Response(200, "unused"),
                (target, text, enter) -> {
                    sentTexts.add(text);
                    sent.countDown();
                    return new WindowsInputSender.Result(true, "sent");
                })) {
            service.configure(automation(false, LogRemoteMatchAction.CONTINUE_INPUT,
                    2, 2, 2), event -> { });

            service.acceptAll(matches(7));

            assertTrue(sent.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("继续", "继续"), sentTexts);
        }
    }

    @Test
    void anySelectedTriggerRuleCanScheduleAutomation() throws Exception {
        List<String> sentTexts = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch sent = new CountDownLatch(2);
        try (LogMonitorAutomationService service = new LogMonitorAutomationService(
                url -> new LogMonitorAutomationService.Response(200, "unused"),
                (target, text, enter) -> {
                    sentTexts.add(text);
                    sent.countDown();
                    return new WindowsInputSender.Result(true, "sent");
                })) {
            service.configure(new LogMonitorAutomation(true, List.of("429", "503"),
                    "Codex", true, "继续", true, 1, 1, 2,
                    false, "https://example.com", "allow",
                    LogRemoteMatchAction.CONTINUE_INPUT), event -> { });

            service.acceptAll(List.of(match("429"), match("503"), match("404")));

            assertTrue(sent.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("继续", "继续"), sentTexts);
        }
    }

    @Test
    void matchingRemoteKeywordCanAllowOrBlockInput() throws Exception {
        CountDownLatch allowed = new CountDownLatch(1);
        List<LogMonitorAutomationService.Event> blockedEvents =
                java.util.Collections.synchronizedList(new ArrayList<>());
        try (LogMonitorAutomationService allowService = new LogMonitorAutomationService(
                url -> new LogMonitorAutomationService.Response(200, "state=allow"),
                (target, text, enter) -> {
                    allowed.countDown();
                    return new WindowsInputSender.Result(true, "sent");
                });
             LogMonitorAutomationService blockService = new LogMonitorAutomationService(
                     url -> new LogMonitorAutomationService.Response(200, "state=allow"),
                     (target, text, enter) -> new WindowsInputSender.Result(true, "unexpected"))) {
            allowService.configure(automation(true, LogRemoteMatchAction.CONTINUE_INPUT,
                    1, 1, 1), event -> { });
            blockService.configure(automation(true, LogRemoteMatchAction.NO_ACTION,
                    1, 1, 1), blockedEvents::add);

            allowService.acceptAll(matches(1));
            blockService.acceptAll(matches(1));

            assertTrue(allowed.await(3, TimeUnit.SECONDS));
            awaitEvents(blockedEvents, 1);
            assertEquals(LogMonitorAutomationService.EventType.SKIPPED,
                    blockedEvents.getFirst().type());
        }
    }

    @Test
    void remoteFailureSkipsInputAndReportsError() throws Exception {
        CountDownLatch eventReceived = new CountDownLatch(1);
        List<LogMonitorAutomationService.Event> events =
                java.util.Collections.synchronizedList(new ArrayList<>());
        try (LogMonitorAutomationService service = new LogMonitorAutomationService(
                url -> new LogMonitorAutomationService.Response(503, "allow"),
                (target, text, enter) -> new WindowsInputSender.Result(true, "unexpected"))) {
            service.configure(automation(true, LogRemoteMatchAction.CONTINUE_INPUT,
                    1, 1, 1), event -> {
                events.add(event);
                eventReceived.countDown();
            });

            service.acceptAll(matches(1));

            assertTrue(eventReceived.await(3, TimeUnit.SECONDS));
            assertEquals(LogMonitorAutomationService.EventType.ERROR, events.getFirst().type());
            assertTrue(events.getFirst().message().contains("HTTP 503"));
        }
    }

    @Test
    void failedRemoteAttemptDoesNotConsumeSuccessfulExecutionLimit() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstError = new CountDownLatch(1);
        CountDownLatch sent = new CountDownLatch(1);
        try (LogMonitorAutomationService service = new LogMonitorAutomationService(
                url -> requests.incrementAndGet() == 1
                        ? new LogMonitorAutomationService.Response(503, "")
                        : new LogMonitorAutomationService.Response(200, "allow"),
                (target, text, enter) -> {
                    sent.countDown();
                    return new WindowsInputSender.Result(true, "sent");
                })) {
            service.configure(automation(true, LogRemoteMatchAction.CONTINUE_INPUT,
                    1, 1, 1), event -> {
                if (event.type() == LogMonitorAutomationService.EventType.ERROR) {
                    firstError.countDown();
                }
            });

            service.acceptAll(matches(1));
            assertTrue(firstError.await(3, TimeUnit.SECONDS));
            service.acceptAll(matches(1));

            assertTrue(sent.await(3, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
        }
    }

    @Test
    void reconfigureClearsOldQueueWithoutDroppingFirstNewAction() throws Exception {
        CountDownLatch oldRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseOldRequest = new CountDownLatch(1);
        CountDownLatch newActionSent = new CountDownLatch(1);
        List<String> sentTexts = java.util.Collections.synchronizedList(new ArrayList<>());
        try (LogMonitorAutomationService service = new LogMonitorAutomationService(url -> {
            oldRequestStarted.countDown();
            try {
                if (!releaseOldRequest.await(3, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("test release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("test interrupted", exception);
            }
            return new LogMonitorAutomationService.Response(200, "allow");
        }, (target, text, enter) -> {
            sentTexts.add(text);
            if ("new".equals(text)) {
                newActionSent.countDown();
            }
            return new WindowsInputSender.Result(true, "sent");
        })) {
            service.configure(automation("old", true, 0), event -> { });
            service.acceptAll(matches(32));
            assertTrue(oldRequestStarted.await(3, TimeUnit.SECONDS));

            service.configure(automation("new", false, 1), event -> { });
            service.acceptAll(matches(1));
            releaseOldRequest.countDown();

            assertTrue(newActionSent.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("new"), sentTexts);
        } finally {
            releaseOldRequest.countDown();
        }
    }

    private static LogMonitorAutomation automation(boolean remote,
                                                   LogRemoteMatchAction action,
                                                   int start, int every, int maximum) {
        return new LogMonitorAutomation(true, "429", "Codex", true, "继续", true,
                start, every, maximum, remote, "https://example.com", "allow", action);
    }

    private static LogMonitorAutomation automation(String text, boolean remote, int maximum) {
        return new LogMonitorAutomation(true, "429", "Codex", true, text, true,
                1, 1, maximum, remote, "https://example.com", "allow",
                LogRemoteMatchAction.CONTINUE_INPUT);
    }

    private static List<LogMonitorMatch> matches(int count) {
        List<LogMonitorMatch> matches = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            matches.add(new LogMonitorMatch("429", "HTTP 429", "429",
                    Path.of("codex.log"), "429", Instant.EPOCH.plusSeconds(index)));
        }
        return matches;
    }

    private static LogMonitorMatch match(String ruleId) {
        return new LogMonitorMatch(ruleId, "HTTP " + ruleId, ruleId,
                Path.of("codex.log"), ruleId, Instant.EPOCH);
    }

    private static void awaitEvents(List<?> events, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (events.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(count, events.size());
    }
}
