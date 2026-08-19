package plugin.javafxtools.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/** Sends Unicode keyboard input to one unambiguously identified Windows application window. */
public final class WindowsInputSender {
    private static final int INPUT_TIMEOUT_SECONDS = 8;

    @FunctionalInterface
    interface ScriptExecutor {
        PowerShellScriptRunner.Result run(String script) throws IOException;
    }

    public record Result(boolean success, String message) {
        public Result {
            Objects.requireNonNull(message, "message");
        }
    }

    private final ScriptExecutor scriptExecutor;
    private final boolean windows;

    public WindowsInputSender() {
        this(script -> PowerShellScriptRunner.run(script, INPUT_TIMEOUT_SECONDS,
                        "自动输入", "log-monitor-input-output"),
                isWindows(System.getProperty("os.name", "")));
    }

    WindowsInputSender(ScriptExecutor scriptExecutor, boolean windows) {
        this.scriptExecutor = Objects.requireNonNull(scriptExecutor, "scriptExecutor");
        this.windows = windows;
    }

    static boolean isWindows(String osName) {
        return osName != null
                && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public Result send(String targetWindow, String text, boolean pressEnter) {
        Objects.requireNonNull(targetWindow, "targetWindow");
        Objects.requireNonNull(text, "text");
        if (!windows) {
            return new Result(false, "自动输入仅支持 Windows");
        }
        try {
            PowerShellScriptRunner.Result result = scriptExecutor.run(
                    buildScript(targetWindow, text, pressEnter));
            return switch (result.exitCode()) {
                case 0 -> new Result(true, "已向目标程序发送自动输入");
                case 2 -> new Result(false, "未找到目标进程，或目标进程没有可见窗口");
                case 3 -> new Result(false, "找到多个目标窗口，请填写 PID 或更具体的窗口标题");
                case 4 -> new Result(false, "Windows 阻止了目标窗口获得焦点，未发送输入");
                case 5 -> new Result(false, "Windows 未发送键盘输入；若目标程序以管理员身份运行，"
                        + "请同样以管理员身份运行 FxTools");
                case 6 -> new Result(true, "Windows 仅发送了部分输入；为避免重复，本次按已执行处理");
                default -> new Result(false, "自动输入进程失败，退出码 " + result.exitCode());
            };
        } catch (IOException | RuntimeException exception) {
            String detail = exception.getMessage();
            return new Result(false, "自动输入失败: "
                    + (detail == null || detail.isBlank()
                    ? exception.getClass().getSimpleName() : detail));
        }
    }

    static String buildScript(String targetWindow, String text, boolean pressEnter) {
        String normalizedTarget = targetWindow.trim();
        ProcessTarget processTarget = parseProcessTarget(normalizedTarget);
        String encodedTarget = base64(normalizedTarget);
        String encodedTargetTitle = base64(processTarget.windowTitle());
        String encodedText = base64(text);
        return """
                $ErrorActionPreference = 'Stop'
                $target = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'))
                $targetTitle = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'))
                $inputText = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'))
                $pressEnter = $%s
                $useProcessId = $%s
                $targetProcessId = [int64]%d

                Add-Type -TypeDefinition @'
                using System;
                using System.Collections.Generic;
                using System.Diagnostics;
                using System.Runtime.InteropServices;
                using System.Text;

                public static class LogMonitorKeyboard {
                    public sealed class WindowInfo {
                        public IntPtr Handle { get; set; }
                        public uint ProcessId { get; set; }
                        public string ProcessName { get; set; }
                        public string Title { get; set; }
                        public bool IsMainWindow { get; set; }
                    }

                    private delegate bool EnumWindowsCallback(IntPtr window, IntPtr parameter);

                    private const uint INPUT_KEYBOARD = 1;
                    private const uint KEYEVENTF_KEYUP = 0x0002;
                    private const uint KEYEVENTF_UNICODE = 0x0004;
                    private const ushort VK_RETURN = 0x0D;
                    [DllImport("user32.dll")]
                    public static extern bool SetForegroundWindow(IntPtr hWnd);

                    [DllImport("user32.dll")]
                    public static extern IntPtr GetForegroundWindow();

                    [DllImport("user32.dll")]
                    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

                    [DllImport("user32.dll")]
                    private static extern bool EnumWindows(EnumWindowsCallback callback,
                        IntPtr parameter);

                    [DllImport("user32.dll")]
                    private static extern bool IsWindowVisible(IntPtr hWnd);

                    [DllImport("user32.dll")]
                    private static extern bool BringWindowToTop(IntPtr hWnd);

                    [DllImport("user32.dll")]
                    private static extern uint GetWindowThreadProcessId(IntPtr hWnd,
                        out uint processId);

                    [DllImport("kernel32.dll")]
                    private static extern uint GetCurrentThreadId();

                    [DllImport("user32.dll")]
                    private static extern bool AttachThreadInput(uint idAttach,
                        uint idAttachTo, bool attach);

                    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
                    private static extern int GetWindowText(IntPtr hWnd,
                        StringBuilder text, int maximum);

                    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
                    private static extern int GetWindowTextLength(IntPtr hWnd);

                    [DllImport("user32.dll", SetLastError = true)]
                    private static extern uint SendInput(uint inputCount, INPUT[] inputs, int size);

                    [StructLayout(LayoutKind.Sequential)]
                    private struct INPUT {
                        public uint type;
                        public INPUTUNION data;
                    }

                    [StructLayout(LayoutKind.Explicit)]
                    private struct INPUTUNION {
                        [FieldOffset(0)] public MOUSEINPUT mouse;
                        [FieldOffset(0)] public KEYBDINPUT keyboard;
                        [FieldOffset(0)] public HARDWAREINPUT hardware;
                    }

                    [StructLayout(LayoutKind.Sequential)]
                    private struct MOUSEINPUT {
                        public int dx;
                        public int dy;
                        public uint mouseData;
                        public uint flags;
                        public uint time;
                        public IntPtr extraInfo;
                    }

                    [StructLayout(LayoutKind.Sequential)]
                    private struct KEYBDINPUT {
                        public ushort virtualKey;
                        public ushort scanCode;
                        public uint flags;
                        public uint time;
                        public IntPtr extraInfo;
                    }

                    [StructLayout(LayoutKind.Sequential)]
                    private struct HARDWAREINPUT {
                        public uint message;
                        public ushort parameterLow;
                        public ushort parameterHigh;
                    }

                    public static WindowInfo[] VisibleWindows() {
                        var windows = new List<WindowInfo>();
                        EnumWindows(delegate(IntPtr window, IntPtr parameter) {
                            if (!IsWindowVisible(window)) return true;
                            uint processId;
                            if (GetWindowThreadProcessId(window, out processId) == 0) return true;
                            try {
                                using (Process process = Process.GetProcessById((int)processId)) {
                                    bool isMainWindow = false;
                                    try {
                                        process.Refresh();
                                        isMainWindow = process.MainWindowHandle == window;
                                    } catch {
                                        // MainWindowHandle can become unavailable while enumerating.
                                    }
                                    windows.Add(new WindowInfo {
                                        Handle = window,
                                        ProcessId = processId,
                                        ProcessName = process.ProcessName,
                                        Title = WindowTitle(window),
                                        IsMainWindow = isMainWindow
                                    });
                                }
                            } catch {
                                // A process can exit or become inaccessible during enumeration.
                            }
                            return true;
                        }, IntPtr.Zero);
                        return windows.ToArray();
                    }

                    public static bool Activate(IntPtr window, uint expectedProcessId,
                                                string requiredTitle) {
                        if (!MatchesWindow(window, expectedProcessId, requiredTitle)) return false;
                        ShowWindowAsync(window, 9);
                        if (GetForegroundWindow() == window) return true;

                        IntPtr foreground = GetForegroundWindow();
                        uint ignored;
                        uint currentThread = GetCurrentThreadId();
                        uint foregroundThread = foreground == IntPtr.Zero ? 0
                            : GetWindowThreadProcessId(foreground, out ignored);
                        uint targetThread = GetWindowThreadProcessId(window, out ignored);
                        bool foregroundAttached = foregroundThread != 0
                            && foregroundThread != currentThread
                            && AttachThreadInput(currentThread, foregroundThread, true);
                        bool targetAttached = targetThread != 0
                            && targetThread != currentThread
                            && targetThread != foregroundThread
                            && AttachThreadInput(currentThread, targetThread, true);
                        try {
                            BringWindowToTop(window);
                            SetForegroundWindow(window);
                        } finally {
                            if (targetAttached) {
                                AttachThreadInput(currentThread, targetThread, false);
                            }
                            if (foregroundAttached) {
                                AttachThreadInput(currentThread, foregroundThread, false);
                            }
                        }
                        return GetForegroundWindow() == window
                            && MatchesWindow(window, expectedProcessId, requiredTitle);
                    }

                    public static int Send(IntPtr window, uint expectedProcessId,
                                           string requiredTitle, string text,
                                           bool pressEnter) {
                        if (GetForegroundWindow() != window
                            || !MatchesWindow(window, expectedProcessId, requiredTitle)) {
                            return 0;
                        }
                        var inputs = new List<INPUT>();
                        foreach (char character in text) {
                            inputs.Add(Key(0, character, KEYEVENTF_UNICODE));
                            inputs.Add(Key(0, character, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
                        }
                        if (pressEnter) {
                            inputs.Add(Key(VK_RETURN, '\\0', 0));
                            inputs.Add(Key(VK_RETURN, '\\0', KEYEVENTF_KEYUP));
                        }
                        if (inputs.Count == 0) return 1;
                        if (GetForegroundWindow() != window
                            || !MatchesWindow(window, expectedProcessId, requiredTitle)) {
                            return 0;
                        }
                        INPUT[] values = inputs.ToArray();
                        uint sent = SendInput((uint)values.Length, values,
                            Marshal.SizeOf(typeof(INPUT)));
                        if (sent == values.Length) return 1;
                        return sent > 0 ? 2 : 0;
                    }

                    private static bool MatchesWindow(IntPtr window, uint expectedProcessId,
                                                      string requiredTitle) {
                        uint actualProcessId;
                        if (GetWindowThreadProcessId(window, out actualProcessId) == 0
                            || actualProcessId != expectedProcessId) {
                            return false;
                        }
                        if (String.IsNullOrEmpty(requiredTitle)) return true;
                        return WindowTitle(window).IndexOf(requiredTitle,
                            StringComparison.OrdinalIgnoreCase) >= 0;
                    }

                    private static string WindowTitle(IntPtr window) {
                        int length = Math.Min(GetWindowTextLength(window), 32767);
                        var title = new StringBuilder(length + 1);
                        GetWindowText(window, title, title.Capacity);
                        return title.ToString();
                    }

                    private static INPUT Key(ushort virtualKey, char scanCode, uint flags) {
                        var input = new INPUT();
                        input.type = INPUT_KEYBOARD;
                        input.data.keyboard.virtualKey = virtualKey;
                        input.data.keyboard.scanCode = scanCode;
                        input.data.keyboard.flags = flags;
                        return input;
                    }
                }
                '@

                $visible = @([LogMonitorKeyboard]::VisibleWindows())
                if ($useProcessId) {
                    $processMatches = @($visible | Where-Object {
                        [int64]$_.ProcessId -eq $targetProcessId
                    })
                    if (-not [String]::IsNullOrEmpty($targetTitle)) {
                        $processMatches = @($processMatches | Where-Object {
                            $_.Title.Equals($targetTitle, [StringComparison]::OrdinalIgnoreCase)
                        })
                    }
                    if ($processMatches.Count -eq 0) { exit 2 }
                    if (-not [String]::IsNullOrEmpty($targetTitle)) {
                        if ($processMatches.Count -ne 1) { exit 3 }
                        $selected = $processMatches[0]
                    } elseif ($processMatches.Count -eq 1) {
                        $selected = $processMatches[0]
                    } else {
                        $mainWindowMatches = @($processMatches | Where-Object { $_.IsMainWindow })
                        if ($mainWindowMatches.Count -ne 1) { exit 3 }
                        $selected = $mainWindowMatches[0]
                    }
                    $requiredTitle = $targetTitle
                } else {
                    $titleMatches = @($visible | Where-Object {
                        $_.Title.IndexOf($target, [StringComparison]::OrdinalIgnoreCase) -ge 0
                    })
                    if ($titleMatches.Count -gt 1) { exit 3 }
                    if ($titleMatches.Count -eq 1) {
                        $selected = $titleMatches[0]
                        $requiredTitle = $target
                    } else {
                        $processTarget = [IO.Path]::GetFileNameWithoutExtension($target)
                        $processMatches = @($visible | Where-Object {
                            $_.ProcessName.Equals($processTarget, [StringComparison]::OrdinalIgnoreCase)
                        })
                        if ($processMatches.Count -eq 0) { exit 2 }
                        if ($processMatches.Count -gt 1) { exit 3 }
                        $selected = $processMatches[0]
                        $requiredTitle = ''
                    }
                }

                $handle = [IntPtr]$selected.Handle
                $processId = [uint32]$selected.ProcessId
                [void][LogMonitorKeyboard]::ShowWindowAsync($handle, 9)
                $shell = New-Object -ComObject WScript.Shell
                [void]$shell.AppActivate([int]$processId)
                Start-Sleep -Milliseconds 150
                $activated = [LogMonitorKeyboard]::Activate($handle, $processId, $requiredTitle)
                Start-Sleep -Milliseconds 150
                if (-not $activated -or [LogMonitorKeyboard]::GetForegroundWindow() -ne $handle) { exit 4 }
                $sendResult = [LogMonitorKeyboard]::Send(
                    $handle, $processId, $requiredTitle, $inputText, $pressEnter)
                if ($sendResult -eq 2) { exit 6 }
                if ($sendResult -ne 1) { exit 5 }
                exit 0
                """.formatted(encodedTarget, encodedTargetTitle, encodedText,
                pressEnter ? "true" : "false",
                processTarget.byProcessId() ? "true" : "false",
                processTarget.processId());
    }

    private static ProcessTarget parseProcessTarget(String target) {
        String candidate = target;
        String windowTitle = "";
        int titleSeparator = target.indexOf('|');
        if (titleSeparator >= 0 && target.regionMatches(true, 0, "pid", 0, 3)) {
            candidate = target.substring(0, titleSeparator).trim();
            windowTitle = target.substring(titleSeparator + 1).trim();
        }
        boolean hasPrefix = candidate.regionMatches(true, 0, "pid", 0, 3);
        if (hasPrefix) {
            candidate = candidate.substring(3).trim();
            if (candidate.startsWith(":") || candidate.startsWith("：")) {
                candidate = candidate.substring(1).trim();
            }
        }
        if ((!hasPrefix && candidate.isEmpty())
                || candidate.chars().anyMatch(character -> !Character.isDigit(character))) {
            return new ProcessTarget(false, 0L, "");
        }
        try {
            long processId = Long.parseLong(candidate);
            return new ProcessTarget(true, processId, windowTitle);
        } catch (NumberFormatException exception) {
            return new ProcessTarget(true, -1L, windowTitle);
        }
    }

    private record ProcessTarget(boolean byProcessId, long processId, String windowTitle) {
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
