package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService.CommandResult;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService.FirmwareType;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService.WakeTimerStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsPowerDiagnosticsServiceTest {

    @Test
    void parsesChineseWindowsDiagnostics() throws Exception {
        CapturingRunner runner = new CapturingRunner(new CommandResult(0, """
                {"systemManufacturer":"IPASON","systemModel":"",\
                "boardManufacturer":"IPASON","boardProduct":"13500H E1",\
                "biosManufacturer":"AMI","biosVersion":"5.27",\
                "biosReleaseDate":"2024-06-19","firmwareType":2,\
                "hibernationConfigured":false,\
                "powerStatesText":"此系统上有以下睡眠状态:\\n待机 (S3)\\n\\n此系统上没有以下睡眠状态:\\n休眠",\
                "wakeDevices":["HID Keyboard Device","Realtek PCIe GbE Family Controller"],\
                "wakeTimerExitCode":1,"wakeTimerText":"此命令需要管理员权限",\
                "biosManagementInterfaces":[]}
                """));
        WindowsPowerDiagnosticsService service = windowsService(runner);

        var diagnostics = service.detect();

        assertEquals("IPASON", diagnostics.systemManufacturer());
        assertEquals("13500H E1", diagnostics.boardProduct());
        assertEquals(FirmwareType.UEFI, diagnostics.firmwareType());
        assertTrue(diagnostics.powerStates().s3Supported());
        assertFalse(diagnostics.powerStates().hibernationAvailable());
        assertFalse(diagnostics.powerStates().hibernationConfigured());
        assertEquals(2, diagnostics.wakeArmedDevices().size());
        assertEquals(WakeTimerStatus.ADMIN_REQUIRED, diagnostics.wakeTimerStatus());
        assertFalse(diagnostics.vendorBiosInterfaceDetected());
    }

    @Test
    void parsesEnglishPowerStateSectionsWithoutReadingUnavailableStatesAsSupported() {
        String output = """
                The following sleep states are available on this system:
                    Standby (S0 Low Power Idle) Network Connected

                The following sleep states are not available on this system:
                    Standby (S3)
                    Hibernate
                    Fast Startup
                """;

        var capabilities = WindowsPowerDiagnosticsService.parsePowerStates(output, false);

        assertFalse(capabilities.s3Supported());
        assertTrue(capabilities.modernStandbySupported());
        assertFalse(capabilities.hibernationAvailable());
        assertFalse(capabilities.fastStartupAvailable());
    }

    @Test
    void reportsVendorBiosInterfaceWhenOfficialClassIsPresent() throws Exception {
        CapturingRunner runner = new CapturingRunner(new CommandResult(0, """
                {"systemManufacturer":"Lenovo","systemModel":"ThinkCentre",\
                "boardManufacturer":"Lenovo","boardProduct":"Board",\
                "biosManufacturer":"Lenovo","biosVersion":"1.0",\
                "biosReleaseDate":"2026-01-01","firmwareType":2,\
                "hibernationConfigured":true,"powerStatesText":"Hibernate",\
                "wakeDevices":[],"wakeTimerExitCode":0,\
                "wakeTimerText":"There are no active wake timers in the system.",\
                "biosManagementInterfaces":["Lenovo WMI"]}
                """));

        var diagnostics = windowsService(runner).detect();

        assertTrue(diagnostics.vendorBiosInterfaceDetected());
        assertEquals(List.of("Lenovo WMI"), diagnostics.biosManagementInterfaces());
        assertEquals(WakeTimerStatus.NONE, diagnostics.wakeTimerStatus());
    }

    @Test
    void diagnosticScriptContainsOnlyReadOperations() throws Exception {
        CapturingRunner runner = new CapturingRunner(new CommandResult(0, minimumJson()));

        windowsService(runner).detect();

        String script = runner.lastScript().toLowerCase();
        assertFalse(script.contains("set-ciminstance"));
        assertFalse(script.contains("set-wmiinstance"));
        assertFalse(script.contains("setfirmwareenvironmentvariable"));
        assertFalse(script.contains("registertaskdefinition"));
        assertFalse(script.contains("powercfg.exe /hibernate"));
        assertTrue(script.contains("get-ciminstance"));
        assertTrue(script.contains("get-cimclass"));
    }

    @Test
    void rejectsDiagnosticsOutsideWindows() {
        CapturingRunner runner = new CapturingRunner(new CommandResult(0, minimumJson()));
        WindowsPowerDiagnosticsService service =
                new WindowsPowerDiagnosticsService("Linux", runner);

        assertThrows(IOException.class, service::detect);
        assertTrue(runner.commands.isEmpty());
    }

    private WindowsPowerDiagnosticsService windowsService(CapturingRunner runner) {
        return new WindowsPowerDiagnosticsService("Windows 11", runner);
    }

    private String minimumJson() {
        return """
                {"systemManufacturer":"","systemModel":"",\
                "boardManufacturer":"","boardProduct":"",\
                "biosManufacturer":"","biosVersion":"","biosReleaseDate":"",\
                "firmwareType":0,"hibernationConfigured":false,"powerStatesText":"",\
                "wakeDevices":[],"wakeTimerExitCode":0,"wakeTimerText":"",\
                "biosManagementInterfaces":[]}
                """;
    }

    private static final class CapturingRunner
            implements WindowsPowerDiagnosticsService.CommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final CommandResult result;

        private CapturingRunner(CommandResult result) {
            this.result = result;
        }

        @Override
        public CommandResult run(List<String> command) {
            commands.add(List.copyOf(command));
            return result;
        }

        private String lastScript() {
            return new String(Base64.getDecoder().decode(
                    commands.getLast().getLast()), StandardCharsets.UTF_16LE);
        }
    }
}
