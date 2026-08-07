package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoggingServiceTest {
    @Test
    void highVolumeLogsUseBoundedBatchedUiDispatch() {
        Deque<Runnable> fxTasks = new ArrayDeque<>();
        List<String> consoleLines = new ArrayList<>();
        LoggingService service = new LoggingService(
                fxTasks::addLast, () -> false, consoleLines::add, consoleLines::add);

        for (int index = 0; index < 1_000; index++) {
            service.info("log-" + index);
        }

        assertEquals(1, fxTasks.size());
        assertEquals(1_000, consoleLines.size());

        int dispatchCount = drainTasks(fxTasks);
        assertEquals(5, dispatchCount,
                "500 条有界积压应按每批 100 条合并刷新");
    }

    @Test
    void clearInvalidatesQueuedLogsWithoutCreatingOneTaskPerMessage() {
        Deque<Runnable> fxTasks = new ArrayDeque<>();
        LoggingService service = new LoggingService(
                fxTasks::addLast, () -> false, ignored -> { }, ignored -> { });

        for (int index = 0; index < 50; index++) {
            service.info("old-" + index);
        }
        service.clearGlobalLogs();
        service.info("new");

        assertEquals(3, fxTasks.size(),
                "清空前刷新、清空动作和清空后刷新必须保持独立顺序");
        assertEquals(3, drainTasks(fxTasks));
    }

    private int drainTasks(Deque<Runnable> tasks) {
        int count = 0;
        while (!tasks.isEmpty()) {
            tasks.removeFirst().run();
            count++;
        }
        return count;
    }
}
