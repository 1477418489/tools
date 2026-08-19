package plugin.javafxtools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Lists visible top-level Windows windows for explicit automation target selection. */
public final class WindowsWindowPickerService {
    private static final int ENUMERATION_TIMEOUT_SECONDS = 8;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @FunctionalInterface
    interface ScriptExecutor {
        PowerShellScriptRunner.Result run(String script) throws IOException;
    }

    public record WindowTarget(long processId, String processName, String windowTitle) {
        public WindowTarget {
            if (processId <= 0) {
                throw new IllegalArgumentException("processId must be positive");
            }
            processName = Objects.requireNonNull(processName, "processName");
            windowTitle = Objects.requireNonNull(windowTitle, "windowTitle");
        }

        public String selector() {
            return "pid:" + processId + " | " + windowTitle;
        }

        @Override
        public String toString() {
            return windowTitle + "  -  " + processName + " (PID " + processId + ")";
        }
    }

    private final ScriptExecutor scriptExecutor;
    private final boolean windows;
    private final long currentProcessId;

    public WindowsWindowPickerService() {
        this(script -> PowerShellScriptRunner.run(script, ENUMERATION_TIMEOUT_SECONDS,
                        "读取窗口", "log-monitor-window-picker-output"),
                isWindows(System.getProperty("os.name", "")), ProcessHandle.current().pid());
    }

    WindowsWindowPickerService(ScriptExecutor scriptExecutor, boolean windows,
                               long currentProcessId) {
        this.scriptExecutor = Objects.requireNonNull(scriptExecutor, "scriptExecutor");
        this.windows = windows;
        this.currentProcessId = currentProcessId;
    }

    public List<WindowTarget> listVisibleWindows() throws IOException {
        if (!windows) {
            throw new IOException("窗口选择仅支持 Windows");
        }
        PowerShellScriptRunner.Result result = scriptExecutor.run(buildScript(currentProcessId));
        if (result.exitCode() != 0) {
            throw new IOException("读取当前窗口失败，退出码 " + result.exitCode());
        }
        return parseTargets(result.output());
    }

    static String buildScript(long currentProcessId) {
        return """
                $ErrorActionPreference = 'Stop'
                [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

                Add-Type -TypeDefinition @'
                using System;
                using System.Collections.Generic;
                using System.Diagnostics;
                using System.Runtime.InteropServices;
                using System.Text;

                public static class LogMonitorWindowCatalog {
                    public sealed class WindowInfo {
                        public uint ProcessId { get; set; }
                        public string ProcessName { get; set; }
                        public string WindowTitle { get; set; }
                    }

                    private delegate bool EnumWindowsCallback(IntPtr window, IntPtr parameter);
                    private const uint GW_OWNER = 4;
                    private const uint DWMWA_CLOAKED = 14;

                    [DllImport("user32.dll")]
                    private static extern bool EnumWindows(EnumWindowsCallback callback,
                        IntPtr parameter);

                    [DllImport("user32.dll")]
                    private static extern bool IsWindowVisible(IntPtr window);

                    [DllImport("user32.dll")]
                    private static extern IntPtr GetWindow(IntPtr window, uint command);

                    [DllImport("user32.dll")]
                    private static extern IntPtr GetShellWindow();

                    [DllImport("dwmapi.dll")]
                    private static extern int DwmGetWindowAttribute(IntPtr window,
                        uint attribute, out int value, int size);

                    [DllImport("user32.dll")]
                    private static extern uint GetWindowThreadProcessId(IntPtr window,
                        out uint processId);

                    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
                    private static extern int GetWindowText(IntPtr window,
                        StringBuilder text, int maximum);

                    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
                    private static extern int GetWindowTextLength(IntPtr window);

                    public static WindowInfo[] VisibleWindows(long excludedProcessId) {
                        var windows = new List<WindowInfo>();
                        EnumWindows(delegate(IntPtr window, IntPtr parameter) {
                            if (!IsWindowVisible(window)
                                || window == GetShellWindow()
                                || GetWindow(window, GW_OWNER) != IntPtr.Zero
                                || IsCloaked(window)) return true;
                            string title = WindowTitle(window);
                            if (String.IsNullOrWhiteSpace(title)) return true;
                            uint processId;
                            if (GetWindowThreadProcessId(window, out processId) == 0
                                || processId == excludedProcessId) return true;
                            try {
                                using (Process process = Process.GetProcessById((int)processId)) {
                                    windows.Add(new WindowInfo {
                                        ProcessId = processId,
                                        ProcessName = process.ProcessName,
                                        WindowTitle = title
                                    });
                                }
                            } catch {
                                // A process can exit or become inaccessible during enumeration.
                            }
                            return true;
                        }, IntPtr.Zero);
                        return windows.ToArray();
                    }

                    private static bool IsCloaked(IntPtr window) {
                        try {
                            int cloaked;
                            return DwmGetWindowAttribute(window, DWMWA_CLOAKED,
                                out cloaked, sizeof(int)) == 0 && cloaked != 0;
                        } catch {
                            return false;
                        }
                    }

                    private static string WindowTitle(IntPtr window) {
                        int length = Math.Min(GetWindowTextLength(window), 32767);
                        var title = new StringBuilder(length + 1);
                        GetWindowText(window, title, title.Capacity);
                        return title.ToString();
                    }
                }
                '@

                $windows = @([LogMonitorWindowCatalog]::VisibleWindows([int64]%d))
                $json = ConvertTo-Json -InputObject $windows -Compress
                [Console]::Out.Write($json)
                """.formatted(currentProcessId);
    }

    static List<WindowTarget> parseTargets(String output) throws IOException {
        String json = output == null ? "" : output.strip();
        if (!json.isEmpty() && json.charAt(0) == '\ufeff') {
            json = json.substring(1);
        }
        if (json.isEmpty()) {
            throw new IOException("窗口列表返回了空结果");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException exception) {
            throw new IOException("无法解析窗口列表", exception);
        }
        if (root == null || !root.isArray()) {
            throw new IOException("窗口列表格式无效");
        }
        List<WindowTarget> targets = new ArrayList<>();
        for (JsonNode item : root) {
            JsonNode processId = item.path("ProcessId");
            JsonNode processName = item.path("ProcessName");
            JsonNode windowTitle = item.path("WindowTitle");
            if (!processId.isIntegralNumber() || !processId.canConvertToLong()
                    || processId.longValue() <= 0
                    || !processName.isTextual() || processName.textValue().isBlank()
                    || !windowTitle.isTextual() || windowTitle.textValue().isBlank()) {
                throw new IOException("窗口列表包含无效数据");
            }
            targets.add(new WindowTarget(processId.longValue(), processName.textValue(),
                    windowTitle.textValue()));
        }
        targets.sort(Comparator
                .comparing(WindowTarget::processName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(WindowTarget::windowTitle, String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(WindowTarget::processId));
        return List.copyOf(targets);
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
