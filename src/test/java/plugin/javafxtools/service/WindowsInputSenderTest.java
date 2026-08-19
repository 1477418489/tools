package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsInputSenderTest {
    @Test
    void scriptUsesBase64DataInsteadOfInterpolatingUserInput() {
        String target = "Codex'; exit 0; #";
        String input = "继续执行'; throw 'bad";

        String script = WindowsInputSender.buildScript(target, input, true);

        assertFalse(script.contains(target));
        assertFalse(script.contains(input));
        assertTrue(script.contains(base64(target)));
        assertTrue(script.contains(base64(input)));
        assertTrue(script.contains("$pressEnter = $true"));
        assertTrue(script.contains("$useProcessId = $false"));
        assertTrue(script.contains("Key(VK_RETURN, '\\0', 0)"));
        assertFalse(script.indexOf('\0') >= 0);
        assertTrue(script.contains("MatchesWindow(window, expectedProcessId, requiredTitle)"));
        assertTrue(script.contains("$requiredTitle = $target"));
        assertFalse(script.contains("TapAlt"));
        assertFalse(script.contains("SetFocus"));
    }

    @Test
    void numericPidSelectsTheProcessMainWindowDirectly() {
        String script = WindowsInputSender.buildScript("pid:6720", "continue", true);

        assertTrue(script.contains("$useProcessId = $true"));
        assertTrue(script.contains("$targetProcessId = [int64]6720"));
        assertTrue(script.contains("[int64]$_.ProcessId -eq $targetProcessId"));
        assertTrue(script.contains("$mainWindowMatches"));
        assertFalse(script.contains("pid:6720"));
    }

    @Test
    void barePidIsAlsoAccepted() {
        String script = WindowsInputSender.buildScript(" 6720 ", "", true);

        assertTrue(script.contains("$useProcessId = $true"));
        assertTrue(script.contains("$targetProcessId = [int64]6720"));
    }

    @Test
    void pickerSelectorRequiresBothPidAndExactWindowTitle() {
        String title = "FXTOOLS_LOG_TARGET";
        String script = WindowsInputSender.buildScript(
                "pid:6720 | " + title, "continue", true);

        assertTrue(script.contains("$useProcessId = $true"));
        assertTrue(script.contains("$targetProcessId = [int64]6720"));
        assertTrue(script.contains(base64(title)));
        assertTrue(script.contains("$_.Title.Equals($targetTitle"));
        assertTrue(script.contains("$processMatches = @($visible | Where-Object"));
        assertTrue(script.contains("if ($processMatches.Count -ne 1) { exit 3 }"));
        assertTrue(script.contains("$requiredTitle = $targetTitle"));
        assertEquals(1, occurrences(script, "$_.Title.Equals($targetTitle"));
        assertFalse(script.contains(title));
    }

    @Test
    void ambiguousWindowExitCodeIsReportedWithoutSuccess() {
        WindowsInputSender sender = new WindowsInputSender(
                script -> new PowerShellScriptRunner.Result(3, ""), true);

        WindowsInputSender.Result result = sender.send("Codex", "继续", true);

        assertFalse(result.success());
        assertTrue(result.message().contains("多个目标窗口"));
    }

    @Test
    void partialSendIsCountedAsExecutedToPreventAutomaticDuplication() {
        WindowsInputSender sender = new WindowsInputSender(
                script -> new PowerShellScriptRunner.Result(6, ""), true);

        WindowsInputSender.Result result = sender.send("Codex", "继续", true);

        assertTrue(result.success());
        assertTrue(result.message().contains("部分输入"));
    }

    @Test
    void unsupportedPlatformNeverStartsPowerShell() {
        AtomicBoolean invoked = new AtomicBoolean();
        WindowsInputSender sender = new WindowsInputSender(script -> {
            invoked.set(true);
            throw new IOException("must not run");
        }, false);

        WindowsInputSender.Result result = sender.send("Codex", "", true);

        assertFalse(invoked.get());
        assertFalse(result.success());
        assertTrue(result.message().contains("仅支持 Windows"));
    }

    @Test
    void platformDetectionDoesNotTreatDarwinAsWindows() {
        assertTrue(WindowsInputSender.isWindows("Windows 11"));
        assertFalse(WindowsInputSender.isWindows("Darwin"));
        assertFalse(WindowsInputSender.isWindows(null));
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = value.indexOf(needle, fromIndex)) >= 0) {
            count++;
            fromIndex += needle.length();
        }
        return count;
    }
}
