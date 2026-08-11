package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService.CommandResult;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService.FirmwareType;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService.WakeTimerStatus;

import java.io.IOException;
import java.util.ArrayList;
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
                "serialNumber":"SN-123","systemUuid":"UUID-456",\
                "boardManufacturer":"IPASON","boardProduct":"13500H E1",\
                "boardSerialNumber":"BOARD-789","processorName":"Intel Core i5-13500H",\
                "processorCores":12,"processorLogicalProcessors":16,"processorMaxClockMhz":4700,\
                "totalPhysicalMemoryBytes":17179869184,\
                "memoryModules":[{"manufacturer":"Kingston","partNumber":"KVR32",\
                "capacityBytes":8589934592,"speedMhz":3200,"configuredSpeedMhz":3200}],\
                "graphicsAdapters":[{"name":"NVIDIA RTX Test","adapterMemoryBytes":8589934592,\
                "driverVersion":"555.1","temperatureCelsius":48.5,"temperatureSource":"NVIDIA-SMI"}],\
                "temperatures":[{"label":"CPU Package","celsius":54.25,\
                "source":"LibreHardwareMonitor"}],\
                "operatingSystem":"Microsoft Windows 11 · 10.0.26100 · Build 26100",\
                "biosManufacturer":"AMI","biosVersion":"5.27",\
                "biosReleaseDate":"2024-06-19 08:30:00",\
                "activePowerPlan":"平衡","powerSupplyStatus":"交流供电 · 82% · 充电中",\
                "firmwareType":2,\
                "hibernationConfigured":false,\
                "powerStatesText":"此系统上有以下睡眠状态:\\n待机 (S3)\\n\\n此系统上没有以下睡眠状态:\\n休眠",\
                "wakeDevices":["HID Keyboard Device","Realtek PCIe GbE Family Controller"],\
                "wakeTimerExitCode":1,"wakeTimerText":"此命令需要管理员权限",\
                "biosManagementInterfaces":[]}
                """));
        WindowsPowerDiagnosticsService service = windowsService(runner);

        var diagnostics = service.detect();

        assertEquals("IPASON", diagnostics.systemManufacturer());
        assertEquals("SN-123", diagnostics.serialNumber());
        assertEquals("UUID-456", diagnostics.systemUuid());
        assertEquals("13500H E1", diagnostics.boardProduct());
        assertEquals("BOARD-789", diagnostics.boardSerialNumber());
        assertEquals("Intel Core i5-13500H", diagnostics.processorName());
        assertEquals(12, diagnostics.processorCores());
        assertEquals(16, diagnostics.processorLogicalProcessors());
        assertEquals(4_700, diagnostics.processorMaxClockMhz());
        assertEquals(17_179_869_184L, diagnostics.totalPhysicalMemoryBytes());
        assertEquals(1, diagnostics.memoryModules().size());
        assertEquals("KVR32", diagnostics.memoryModules().getFirst().partNumber());
        assertEquals(1, diagnostics.graphicsAdapters().size());
        assertTrue(diagnostics.graphicsAdapters().getFirst().hasTemperature());
        assertEquals(48.5,
                diagnostics.graphicsAdapters().getFirst().temperatureCelsius(), 0.01);
        assertEquals(54.25, diagnostics.temperatures().getFirst().celsius(), 0.01);
        assertEquals("2024-06-19 08:30:00", diagnostics.biosReleaseDate());
        assertEquals("平衡", diagnostics.activePowerPlan());
        assertTrue(diagnostics.powerSupplyStatus().contains("82%"));
        assertTrue(diagnostics.operatingSystem().contains("Windows 11"));
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
        assertTrue(script.contains("get-fxtoolsciminstances"));
        assertTrue(script.contains("get-fxtoolsregistryvalue"));
        assertTrue(script.contains("microsoft.visualbasic.devices.computerinfo"));
        assertTrue(script.contains("$windowsbuildnumber -ge 22000"));
        assertTrue(script.contains("get-cimclass"));
        assertTrue(script.contains("win32_computersystemproduct"));
        assertTrue(script.contains("win32_processor"));
        assertTrue(script.contains("win32_operatingsystem"));
        assertTrue(script.contains("win32_physicalmemory"));
        assertTrue(script.contains("win32_videocontroller"));
        assertTrue(script.contains("nvidia-smi"));
        assertTrue(script.contains("msacpi_thermalzonetemperature"));
        assertTrue(script.contains("powercfg.exe /getactivescheme"));
        assertTrue(script.contains("getsystempowerstatus"));
    }

    @Test
    void rejectsDiagnosticsOutsideWindows() {
        CapturingRunner runner = new CapturingRunner(new CommandResult(0, minimumJson()));
        WindowsPowerDiagnosticsService service =
                new WindowsPowerDiagnosticsService("Linux", runner);

        assertThrows(IOException.class, service::detect);
        assertTrue(runner.scripts.isEmpty());
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
        private final List<String> scripts = new ArrayList<>();
        private final CommandResult result;

        private CapturingRunner(CommandResult result) {
            this.result = result;
        }

        @Override
        public CommandResult run(String script) {
            scripts.add(script);
            return result;
        }

        private String lastScript() {
            return scripts.getLast();
        }
    }
}
