package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsWindowPickerServiceTest {
    @Test
    void parsesSortsAndFormatsVisibleWindows() throws Exception {
        String json = """
                [{"ProcessId":42,"ProcessName":"WindowsTerminal","WindowTitle":"命令提示符"},
                 {"ProcessId":7,"ProcessName":"idea64","WindowTitle":"tools"}]
                """;

        List<WindowsWindowPickerService.WindowTarget> targets =
                WindowsWindowPickerService.parseTargets(json);

        assertEquals(2, targets.size());
        assertEquals(7, targets.getFirst().processId());
        assertEquals("pid:42 | 命令提示符", targets.get(1).selector());
        assertTrue(targets.get(1).toString().contains("WindowsTerminal"));
        assertTrue(targets.get(1).toString().contains("PID 42"));
    }

    @Test
    void enumerationScriptExcludesFxToolsAndWritesJson() {
        String script = WindowsWindowPickerService.buildScript(13120);

        assertTrue(script.contains("VisibleWindows([int64]13120)"));
        assertTrue(script.contains("ConvertTo-Json -InputObject $windows -Compress"));
        assertTrue(script.contains("IsWindowVisible(window)"));
        assertTrue(script.contains("window == GetShellWindow()"));
        assertTrue(script.contains("GetWindow(window, GW_OWNER)"));
        assertTrue(script.contains("IsCloaked(window)"));
        assertTrue(script.contains("String.IsNullOrWhiteSpace(title)"));
    }

    @Test
    void unsupportedPlatformDoesNotStartPowerShell() {
        AtomicBoolean invoked = new AtomicBoolean();
        WindowsWindowPickerService service = new WindowsWindowPickerService(script -> {
            invoked.set(true);
            return new PowerShellScriptRunner.Result(0, "[]");
        }, false, 1);

        IOException exception = assertThrows(IOException.class, service::listVisibleWindows);

        assertFalse(invoked.get());
        assertTrue(exception.getMessage().contains("仅支持 Windows"));
    }

    @Test
    void malformedOrInvalidOutputIsRejected() {
        assertThrows(IOException.class,
                () -> WindowsWindowPickerService.parseTargets("not json"));
        assertThrows(IOException.class,
                () -> WindowsWindowPickerService.parseTargets("{}"));
        assertThrows(IOException.class, () -> WindowsWindowPickerService.parseTargets(
                "[{\"ProcessId\":0,\"ProcessName\":\"cmd\",\"WindowTitle\":\"title\"}]"));
        assertThrows(IOException.class, () -> WindowsWindowPickerService.parseTargets(
                "[{\"ProcessId\":1.5,\"ProcessName\":\"cmd\",\"WindowTitle\":\"title\"}]"));
    }

    @Test
    void nonzeroPowerShellExitIsReported() {
        WindowsWindowPickerService service = new WindowsWindowPickerService(
                script -> new PowerShellScriptRunner.Result(9, "failed"), true, 1);

        IOException exception = assertThrows(IOException.class, service::listVisibleWindows);

        assertTrue(exception.getMessage().contains("退出码 9"));
    }
}
