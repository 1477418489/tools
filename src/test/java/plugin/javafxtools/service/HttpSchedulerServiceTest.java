package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.HttpRequestResult;
import plugin.javafxtools.model.HttpScheduleConfig;
import plugin.javafxtools.util.TimeUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSchedulerServiceTest {
    @Test
    void responseBodyIsLoggedExactlyOnce() throws Exception {
        HttpResponseFormatter formatter = new HttpResponseFormatter();
        CountDownLatch responseLogged = new CountDownLatch(1);
        AtomicReference<String> responseMessage = new AtomicReference<>();
        HttpSchedulerService scheduler = new HttpSchedulerService(
                new ImmediateRequestService(),
                formatter,
                message -> {
                    if (message.startsWith("请求完成")) {
                        responseMessage.set(message);
                        responseLogged.countDown();
                    }
                },
                _ -> { },
                _ -> { },
                _ -> { },
                () -> { },
                () -> { },
                Runnable::run
        );

        try {
            scheduler.start(currentConfig());
            assertTrue(responseLogged.await(2, TimeUnit.SECONDS));

            String message = responseMessage.get();
            assertEquals(1, occurrences(message, "{\"ok\":true}"));
            assertTrue(message.contains("响应状态: 200"));
            assertTrue(message.contains("[响应体]"));
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void stopIsIdempotentAndSuppressesAResponseFromThePreviousRun() throws Exception {
        BlockingRequestService requestService = new BlockingRequestService();
        HttpResponseFormatter formatter = new HttpResponseFormatter();
        AtomicInteger rawResponseCount = new AtomicInteger();
        AtomicInteger runningStateCount = new AtomicInteger();
        AtomicInteger stoppedStateCount = new AtomicInteger();
        HttpSchedulerService scheduler = new HttpSchedulerService(
                requestService,
                formatter,
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

    private HttpSchedulerService schedulerForValidation(AtomicInteger errors,
                                                         AtomicInteger runningStateCount) {
        HttpResponseFormatter formatter = new HttpResponseFormatter();
        return new HttpSchedulerService(
                new HttpRequestService(), formatter,
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
            return new HttpRequestResult("响应状态: 200\nX-Test: value\n", "{\"ok\":true}");
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
            return new HttpRequestResult("完成", "{}");
        }
    }
}
