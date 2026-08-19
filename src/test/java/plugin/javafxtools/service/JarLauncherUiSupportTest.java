package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JarLauncherUiSupportTest {
    @Test
    void coalescesHighVolumeLogsIntoOneScheduledFlush() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        JarLauncherUiSupport support = new JarLauncherUiSupport(
                null, scheduled::add, () -> false);

        for (int index = 0; index < 10_000; index++) {
            support.appendLog("message-" + index);
        }

        assertEquals(1, scheduled.size());
        int flushes = drain(scheduled);
        assertEquals(5, flushes,
                "the bounded 500-message queue should drain in five batches");
    }

    @Test
    void shutdownInvalidatesScheduledFlushAndRejectsNewLogs() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        JarLauncherUiSupport support = new JarLauncherUiSupport(
                null, scheduled::add, () -> false);
        support.appendLog("before shutdown");

        support.shutdown();
        support.appendLog("after shutdown");
        int flushes = drain(scheduled);

        assertEquals(1, flushes);
        assertEquals(0, scheduled.size());
    }

    private static int drain(Queue<Runnable> scheduled) {
        int count = 0;
        Runnable task;
        while ((task = scheduled.poll()) != null) {
            task.run();
            count++;
        }
        return count;
    }
}
