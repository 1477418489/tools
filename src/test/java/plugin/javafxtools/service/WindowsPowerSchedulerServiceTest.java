package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.service.WindowsPowerSchedulerService.CommandResult;
import plugin.javafxtools.service.WindowsPowerSchedulerService.PowerAction;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPowerSchedulerServiceTest {

    @Test
    void rejectsPowerOperationsOutsideWindows() {
        CapturingRunner runner = new CapturingRunner();
        WindowsPowerSchedulerService service =
                new WindowsPowerSchedulerService("Linux", runner);

        assertFalse(service.isSupported());
        assertThrows(IOException.class, () -> service.scheduleWake(futureTime()));
        assertTrue(runner.scripts.isEmpty());
    }

    @Test
    void createsPersistentShutdownTaskWithCountdown() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        WindowsPowerSchedulerService service = windowsService(runner);
        LocalDateTime scheduledFor = LocalDateTime.of(2099, 5, 6, 23, 45);

        service.schedulePowerAction(scheduledFor, PowerAction.SHUTDOWN);

        String script = runner.lastScript();
        assertTrue(script.contains("FxTools-PowerAction"));
        assertTrue(script.contains("2099-05-06T23:45:00"));
        assertTrue(script.contains("/s /t 60"));
        assertTrue(script.contains("RegisterTaskDefinition"));
    }

    @Test
    void wakeTaskEnablesWindowsWakeTimers() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        WindowsPowerSchedulerService service = windowsService(runner);

        service.scheduleWake(futureTime());

        String script = runner.lastScript();
        assertTrue(script.contains("FxTools-Wake"));
        assertTrue(script.contains("WakeToRun=$true"));
        assertTrue(script.contains("StartWhenAvailable=$true"));
    }

    @Test
    void parsesExistingPowerTaskAndAction() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        runner.results.add(new CommandResult(0,
                "FOUND|3|2099-05-06T23:45:00|/r /t 60\r\n"));
        WindowsPowerSchedulerService service = windowsService(runner);

        var status = service.queryPowerTask();

        assertTrue(status.exists());
        assertEquals(LocalDateTime.of(2099, 5, 6, 23, 45), status.nextRunTime());
        assertEquals("3", status.schedulerState());
        assertEquals(PowerAction.RESTART, status.powerAction());
    }

    @Test
    void parsesMissingWakeTask() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        runner.results.add(new CommandResult(0, "\uFEFFMISSING\r\n"));
        WindowsPowerSchedulerService service = windowsService(runner);

        var status = service.queryWakeTask();

        assertFalse(status.exists());
        assertNull(status.nextRunTime());
        assertNull(status.powerAction());
        assertTrue(runner.lastScript().contains("-2147024894,-2147024893"));
    }

    @Test
    void cancelPowerTaskAlsoAbortsActiveShutdownCountdown() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        WindowsPowerSchedulerService service = windowsService(runner);

        service.cancelPowerAction();

        String script = runner.lastScript();
        assertTrue(script.contains("DeleteTask"));
        assertTrue(script.contains("shutdown.exe\" /a"));
    }

    @Test
    void exposesTaskSchedulerFailureDetails() {
        CapturingRunner runner = new CapturingRunner();
        runner.results.add(new CommandResult(1, "Access denied"));
        WindowsPowerSchedulerService service = windowsService(runner);

        IOException error = assertThrows(IOException.class,
                () -> service.scheduleWake(futureTime()));

        assertTrue(error.getMessage().contains("Access denied"));
    }

    @Test
    void extractsReadableFailureFromPowerShellClixmlNoise() {
        CapturingRunner runner = new CapturingRunner();
        runner.results.add(new CommandResult(1, """
                #< CLIXML
                ERROR|拒绝访问
                <Objs><S S="Error">serialized noise</S></Objs>
                """));
        WindowsPowerSchedulerService service = windowsService(runner);

        IOException error = assertThrows(IOException.class,
                () -> service.scheduleWake(futureTime()));

        assertTrue(error.getMessage().contains("拒绝访问"));
        assertFalse(error.getMessage().contains("CLIXML"));
    }

    private WindowsPowerSchedulerService windowsService(CapturingRunner runner) {
        return new WindowsPowerSchedulerService("Windows 11", runner);
    }

    private LocalDateTime futureTime() {
        return LocalDateTime.now().plusDays(1);
    }

    private static final class CapturingRunner
            implements WindowsPowerSchedulerService.CommandRunner {
        private final List<String> scripts = new ArrayList<>();
        private final Deque<CommandResult> results = new ArrayDeque<>();

        @Override
        public CommandResult run(String script) {
            scripts.add(script);
            return results.isEmpty() ? new CommandResult(0, "") : results.removeFirst();
        }

        private String lastScript() {
            return scripts.getLast();
        }
    }
}
