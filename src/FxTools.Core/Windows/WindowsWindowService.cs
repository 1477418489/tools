using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace FxTools.Core.Windows;

public static class WindowsWindowService
{
    private const uint GwOwner = 4;
    private const uint DwmwaCloaked = 14;
    private const int SwRestore = 9;
    private const uint InputKeyboard = 1;
    private const uint KeyEventKeyUp = 0x0002;
    private const uint KeyEventUnicode = 0x0004;
    private const ushort VkReturn = 0x0D;

    public static IReadOnlyList<WindowTarget> ListVisibleWindows(int excludedProcessId = 0)
    {
        EnsureWindows();
        List<WindowTarget> windows = [];
        nint shell = NativeMethods.GetShellWindow();
        NativeMethods.EnumWindows((window, parameter) =>
        {
            if (!NativeMethods.IsWindowVisible(window)
                || window == shell
                || NativeMethods.GetWindow(window, GwOwner) != nint.Zero
                || IsCloaked(window))
            {
                return true;
            }
            string title = GetTitle(window);
            _ = parameter;
            _ = NativeMethods.GetWindowThreadProcessId(window, out uint processId);
            if (string.IsNullOrWhiteSpace(title) || processId == 0 || processId == excludedProcessId)
            {
                return true;
            }
            try
            {
                using Process process = Process.GetProcessById(checked((int)processId));
                windows.Add(new(checked((int)processId), process.ProcessName, title));
            }
            catch (Exception exception) when (exception is ArgumentException or InvalidOperationException or Win32Exception)
            {
            }
            return true;
        }, nint.Zero);
        return windows.OrderBy(window => window.ProcessName, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(window => window.WindowTitle, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(window => window.ProcessId).ToArray();
    }

    public static async Task<InputSendResult> SendInputAsync(
        WindowTarget target,
        string text,
        bool pressEnter,
        CancellationToken cancellationToken = default)
    {
        EnsureWindows();
        ArgumentNullException.ThrowIfNull(target);
        ArgumentNullException.ThrowIfNull(text);
        if (text.Length > 4096) { throw new ArgumentException("自动输入文本不能超过 4096 个字符。", nameof(text)); }
        if (text.Length == 0 && !pressEnter) { return new(false, "没有需要发送的输入。"); }
        List<nint> matches = FindExactWindows(target);
        if (matches.Count == 0) { return new(false, "目标 PID 已退出，或完整窗口标题已变化。"); }
        if (matches.Count > 1) { return new(false, "目标 PID 下存在多个同标题窗口，已拒绝发送。"); }
        nint window = matches[0];
        if (!await ActivateAsync(window, target, cancellationToken).ConfigureAwait(false))
        {
            return new(false, "Windows 未允许目标窗口获得前台焦点，未发送输入。");
        }
        if (!Matches(window, target) || NativeMethods.GetForegroundWindow() != window)
        {
            return new(false, "发送前目标身份或前台窗口已变化，未发送输入。");
        }

        Input[] inputs = BuildInputs(text, pressEnter);
        uint sent = NativeMethods.SendInput(checked((uint)inputs.Length), inputs, Marshal.SizeOf<Input>());
        if (sent == inputs.Length)
        {
            return new(true, "已向选定窗口发送输入。");
        }
        int error = Marshal.GetLastWin32Error();
        return sent == 0
            ? new(false, error == 0
                ? "Windows 未发送输入；若目标以管理员身份运行，请同样提升 FxTools 权限。"
                : new Win32Exception(error).Message)
            : new(true, $"仅发送了部分输入（{sent}/{inputs.Length}），为避免重复未重试。");
    }

    public static WindowTarget ParseSelector(string selector)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(selector);
        const string prefix = "pid:";
        int separator = selector.IndexOf('|');
        if (!selector.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) || separator < 0
            || !int.TryParse(selector.AsSpan(prefix.Length, separator - prefix.Length).Trim(), out int processId)
            || processId <= 0)
        {
            throw new ArgumentException("目标必须由窗口选择器产生，格式为 pid:PID | 完整标题。", nameof(selector));
        }
        string title = selector[(separator + 1)..].Trim();
        if (title.Length == 0) { throw new ArgumentException("目标窗口标题不能为空。", nameof(selector)); }
        string processName = string.Empty;
        try { using Process process = Process.GetProcessById(processId); processName = process.ProcessName; }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException) { }
        return new(processId, processName, title);
    }

    private static List<nint> FindExactWindows(WindowTarget target)
    {
        List<nint> matches = [];
        NativeMethods.EnumWindows((window, _) =>
        {
            if (NativeMethods.IsWindowVisible(window) && Matches(window, target)) { matches.Add(window); }
            return true;
        }, nint.Zero);
        return matches;
    }

    private static bool Matches(nint window, WindowTarget target)
    {
        _ = NativeMethods.GetWindowThreadProcessId(window, out uint processId);
        return processId == target.ProcessId
               && string.Equals(GetTitle(window), target.WindowTitle, StringComparison.Ordinal);
    }

    private static async Task<bool> ActivateAsync(nint window, WindowTarget target, CancellationToken cancellationToken)
    {
        _ = NativeMethods.ShowWindowAsync(window, SwRestore);
        uint foregroundThread = NativeMethods.GetWindowThreadProcessId(NativeMethods.GetForegroundWindow(), out _);
        uint targetThread = NativeMethods.GetWindowThreadProcessId(window, out _);
        uint currentThread = NativeMethods.GetCurrentThreadId();
        bool attachedForeground = foregroundThread != 0 && foregroundThread != currentThread
                                  && NativeMethods.AttachThreadInput(currentThread, foregroundThread, true);
        bool attachedTarget = targetThread != 0 && targetThread != currentThread
                              && NativeMethods.AttachThreadInput(currentThread, targetThread, true);
        try
        {
            _ = NativeMethods.BringWindowToTop(window);
            _ = NativeMethods.SetForegroundWindow(window);
        }
        finally
        {
            if (attachedTarget) { _ = NativeMethods.AttachThreadInput(currentThread, targetThread, false); }
            if (attachedForeground) { _ = NativeMethods.AttachThreadInput(currentThread, foregroundThread, false); }
        }
        for (int attempt = 0; attempt < 10; attempt++)
        {
            if (NativeMethods.GetForegroundWindow() == window && Matches(window, target)) { return true; }
            await Task.Delay(50, cancellationToken).ConfigureAwait(false);
        }
        return false;
    }

    private static Input[] BuildInputs(string text, bool pressEnter)
    {
        List<Input> inputs = new(text.Length * 2 + (pressEnter ? 2 : 0));
        foreach (char character in text)
        {
            inputs.Add(CreateKeyboardInput(0, character, KeyEventUnicode));
            inputs.Add(CreateKeyboardInput(0, character, KeyEventUnicode | KeyEventKeyUp));
        }
        if (pressEnter)
        {
            inputs.Add(CreateKeyboardInput(VkReturn, (char)0, 0));
            inputs.Add(CreateKeyboardInput(VkReturn, (char)0, KeyEventKeyUp));
        }
        return inputs.ToArray();
    }

    private static Input CreateKeyboardInput(ushort virtualKey, char scanCode, uint flags) => new()
    {
        Type = InputKeyboard,
        Data = new InputUnion { Keyboard = new KeyboardInput { VirtualKey = virtualKey, ScanCode = scanCode, Flags = flags } }
    };

    private static unsafe string GetTitle(nint window)
    {
        int length = Math.Min(NativeMethods.GetWindowTextLength(window), 32767);
        if (length <= 0) { return string.Empty; }
        char[] title = new char[length + 1];
        fixed (char* pointer = title)
        {
            int copied = NativeMethods.GetWindowText(window, pointer, title.Length);
            return copied <= 0 ? string.Empty : new string(title, 0, copied);
        }
    }

    private static bool IsCloaked(nint window) =>
        NativeMethods.DwmGetWindowAttribute(window, DwmwaCloaked, out int cloaked, sizeof(int)) == 0
        && cloaked != 0;

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows()) { throw new PlatformNotSupportedException("窗口自动化仅支持 Windows。"); }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input { public uint Type; public InputUnion Data; }
    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MouseInput Mouse;
        [FieldOffset(0)] public KeyboardInput Keyboard;
        [FieldOffset(0)] public HardwareInput Hardware;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput { public int Dx; public int Dy; public uint MouseData; public uint Flags; public uint Time; public nint ExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput { public ushort VirtualKey; public ushort ScanCode; public uint Flags; public uint Time; public nint ExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    private struct HardwareInput { public uint Message; public ushort ParameterLow; public ushort ParameterHigh; }

    private delegate bool EnumWindowsCallback(nint window, nint parameter);

    private static class NativeMethods
    {
        [DllImport("user32.dll")] internal static extern bool EnumWindows(EnumWindowsCallback callback, nint parameter);
        [DllImport("user32.dll")][return: MarshalAs(UnmanagedType.Bool)] internal static extern bool IsWindowVisible(nint window);
        [DllImport("user32.dll")] internal static extern nint GetWindow(nint window, uint command);
        [DllImport("user32.dll")] internal static extern nint GetShellWindow();
        [DllImport("user32.dll")] internal static extern uint GetWindowThreadProcessId(nint window, out uint processId);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] internal static extern unsafe int GetWindowText(nint window, char* text, int maximum);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] internal static extern int GetWindowTextLength(nint window);
        [DllImport("dwmapi.dll")] internal static extern int DwmGetWindowAttribute(nint window, uint attribute, out int value, int size);
        [DllImport("user32.dll")] internal static extern bool SetForegroundWindow(nint window);
        [DllImport("user32.dll")] internal static extern nint GetForegroundWindow();
        [DllImport("user32.dll")] internal static extern bool ShowWindowAsync(nint window, int command);
        [DllImport("user32.dll")] internal static extern bool BringWindowToTop(nint window);
        [DllImport("kernel32.dll")] internal static extern uint GetCurrentThreadId();
        [DllImport("user32.dll")] internal static extern bool AttachThreadInput(uint attach, uint attachTo, bool value);
        [DllImport("user32.dll", SetLastError = true)] internal static extern uint SendInput(uint count, Input[] inputs, int size);
    }
}

public sealed record WindowTarget(int ProcessId, string ProcessName, string WindowTitle)
{
    public string Selector => $"pid:{ProcessId} | {WindowTitle}";
    public string DisplayName => $"{WindowTitle}  -  {ProcessName} (PID {ProcessId})";
}

public readonly record struct InputSendResult(bool Success, string Message);
