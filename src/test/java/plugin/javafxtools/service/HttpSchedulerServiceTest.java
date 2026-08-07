package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.HttpRequestResult;
import plugin.javafxtools.model.HttpScheduleConfig;
import plugin.javafxtools.util.TimeUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSchedulerServiceTest {
    @Test
    void oneShotPublishesResponseBeforeRestoringStoppedState() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch stopped = new CountDownLatch(1);
        HttpSchedulerService scheduler = new HttpSchedulerService(
                new ImmediateRequestService(),
                _ -> { }, _ -> { }, _ -> { },
                _ -> events.add("response"),
                () -> events.add("running"),
                () -> {
                    events.add("stopped");
                    stopped.countDown();
                },
                Runnable::run);

        scheduler.executeOnce(oneShotConfig());

        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("running", "response", "stopped"), events);
    }

    @Test
    void cancellingOneShotSuppressesItsLateResponse() throws Exception {
        BlockingRequestService requestService = new BlockingRequestService();
        AtomicInteger responseCount = new AtomicInteger();
        AtomicInteger stoppedStateCount = new AtomicInteger();
        HttpSchedulerService scheduler = new HttpSchedulerService(
                requestService,
                _ -> { }, _ -> { }, _ -> { },
                _ -> responseCount.incrementAndGet(),
                () -> { }, stoppedStateCount::incrementAndGet,
                Runnable::run);

        try {
            scheduler.executeOnce(oneShotConfig());
            assertTrue(requestService.started.await(2, TimeUnit.SECONDS));
            scheduler.stop();
            requestService.release.countDown();

            assertTrue(requestService.finished.await(2, TimeUnit.SECONDS));
            assertEquals(0, responseCount.get());
            assertEquals(1, stoppedStateCount.get());
        } finally {
            requestService.release.countDown();
            scheduler.stop();
        }
    }

    @Test
    void failedOneShotReportsErrorAndRestoresStoppedState() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        HttpSchedulerService scheduler = new HttpSchedulerService(
                new FailingRequestService(),
                _ -> { }, _ -> { }, _ -> errors.incrementAndGet(), _ -> { },
                () -> { }, stopped::countDown, Runnable::run);

        scheduler.executeOnce(oneShotConfig());

        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        assertEquals(1, errors.get());
    }

    @Test
    void responseIsPublishedOnceWithoutDuplicatingBodyIntoLogs() throws Exception {
        CountDownLatch responsePublished = new CountDownLatch(1);
        AtomicReference<HttpRequestResult> publishedResponse = new AtomicReference<>();
        AtomicReference<String> completionMessage = new AtomicReference<>();
        HttpSchedulerService scheduler = new HttpSchedulerService(
                new ImmediateRequestService(),
                message -> {
                    if (message.startsWith("请求完成")) {
                        completionMessage.set(message);
                    }
                },
                _ -> { },
                _ -> { },
                result -> {
                    publishedResponse.set(result);
                    responsePublished.countDown();
                },
                () -> { },
                () -> { },
                Runnable::run
        );

        try {
            scheduler.start(currentConfig());
            assertTrue(responsePublished.await(2, TimeUnit.SECONDS));
            assertEquals("{\"ok\":true}", publishedResponse.get().rawBody());
            assertTrue(completionMessage.get().contains("HTTP 200"));
            assertEquals(0, occurrences(completionMessage.get(), "{\"ok\":true}"));
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void stopIsIdempotentAndSuppressesAResponseFromThePreviousRun() throws Exception {
        BlockingRequestService requestService = new BlockingRequestService();
        AtomicInteger rawResponseCount = new AtomicInteger();
        AtomicInteger runningStateCount = new AtomicInteger();
        AtomicInteger stoppedStateCount = new AtomicInteger();
        HttpSchedulerService scheduler = new HttpSchedulerService(
                requestService,
                _ -> { },
                _ -> { },
                _ -> { },
                _ -> rawResponseCount.incrementAndGet(),
                runningStateCount::incrementAndGet,
                stoppedStateCount::incrementAndGet,
                Runnable::run
        );

        try {
            scheduler.start(currentConfig());
            scheduler.start(currentConfig());
            assertTrue(requestService.started.await(2, TimeUnit.SECONDS));

            scheduler.stop();
            scheduler.stop();
            requestService.release.countDown();

            assertTrue(requestService.finished.await(2, TimeUnit.SECONDS));
            assertEquals(1, runningStateCount.get());
            assertEquals(1, stoppedStateCount.get());
            assertEquals(0, rawResponseCount.get());
        } finally {
            requestService.release.countDown();
            scheduler.stop();
        }
    }

    @Test
    void responsePublicationRunsThroughTheUiDispatcher() throws Exception {
        BlockingQueue<Runnable> uiActions = new LinkedBlockingQueue<>();
        AtomicInteger responseCount = new AtomicInteger();
        HttpSchedulerService scheduler = new HttpSchedulerService(
                new ImmediateRequestService(),
                _ -> { }, _ -> { }, _ -> { },
                _ -> responseCount.incrementAndGet(),
                () -> { }, () -> { }, uiActions::add);

        try {
            scheduler.start(currentConfig());
            Runnable firstAction = uiActions.poll(2, TimeUnit.SECONDS);
            Runnable secondAction = uiActions.poll(2, TimeUnit.SECONDS);
            assertTrue(firstAction != null);
            assertTrue(secondAction != null);
            assertEquals(0, responseCount.get());
            firstAction.run();
            secondAction.run();
            assertEquals(1, responseCount.get());
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void invalidUrlIsRejectedBeforeSchedulerStarts() {
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger runningStateCount = new AtomicInteger();
        HttpSchedulerService scheduler = schedulerForValidation(errors, runningStateCount);

        HttpScheduleConfig config = new HttpScheduleConfig(
                TimeUtils.getCurrentDateTime(), "1", "file:///tmp/data.json", "GET",
                "", "", "100", "100", "Raw");
        scheduler.start(config);

        assertEquals(1, errors.get());
        assertEquals(0, runningStateCount.get());
    }

    @Test
    void invalidTimeoutIsRejectedInsteadOfSilentlyUsingDefault() {
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger runningStateCount = new AtomicInteger();
        HttpSchedulerService scheduler = schedulerForValidation(errors, runningStateCount);

        HttpScheduleConfig config = new HttpScheduleConfig(
                TimeUtils.getCurrentDateTime(), "1", "https://example.com", "GET",
                "", "", "0", "100", "Raw");
        scheduler.start(config);

        assertEquals(1, errors.get());
        assertEquals(0, runningStateCount.get());
    }

    @Test
    void oneShotDoesNotRequireScheduleFields() throws Exception {
        CountDownLatch response = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        HttpSchedulerService scheduler = new HttpSchedulerService(
                new ImmediateRequestService(),
                _ -> { }, _ -> { }, _ -> errors.incrementAndGet(), _ -> response.countDown(),
                () -> { }, () -> { }, Runnable::run);

        scheduler.executeOnce(oneShotConfig());

        assertTrue(response.await(2, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
    }

    private HttpSchedulerService schedulerForValidation(AtomicInteger errors,
                                                         AtomicInteger runningStateCount) {
        return new HttpSchedulerService(
                new HttpRequestService(),
                _ -> { }, _ -> { }, _ -> errors.incrementAndGet(), _ -> { },
                runningStateCount::incrementAndGet, () -> { }, Runnable::run);
    }

    private HttpScheduleConfig currentConfig() {
        return new HttpScheduleConfig(
                TimeUtils.getCurrentDateTime(),
                "1",
                "https://example.com",
                "GET",
                "",
                "",
                "100",
                "100",
                "Raw"
        );
    }

    private HttpScheduleConfig oneShotConfig() {
        return new HttpScheduleConfig(
                "", "", "https://example.com", "GET",
                "", "", "100", "100", "Raw");
    }

    private int occurrences(String text, String value) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    private static final class ImmediateRequestService extends HttpRequestService {
        private ImmediateRequestService() {
            super();
        }

        @Override
        public HttpRequestResult sendRequest(String urlStr,
                                             String method,
                                             String params,
                                             String headers,
                                             int connectTimeout,
                                             int readTimeout) {
            return new HttpRequestResult(200, 12, "X-Test: value\n", "{\"ok\":true}");
        }
    }

    private static final class BlockingRequestService extends HttpRequestService {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        private BlockingRequestService() {
            super();
        }

        @Override
        public HttpRequestResult sendRequest(String urlStr,
                                             String method,
                                             String params,
                                             String headers,
                                             int connectTimeout,
                                             int readTimeout) {
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Simulate an I/O operation that does not finish immediately when cancelled.
                }
            }
            finished.countDown();
            return new HttpRequestResult(200, 1, "", "{}");
        }
    }

    private static final class FailingRequestService extends HttpRequestService {
        @Override
        public HttpRequestResult sendRequest(String urlStr,
                                             String method,
                                             String params,
                                             String headers,
                                             int connectTimeout,
                                             int readTimeout) throws IOException {
            throw new IOException("connection failed");
        }
    }
}
