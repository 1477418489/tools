using System.ComponentModel;
using System.Runtime.InteropServices;

namespace FxTools.Core.Windows;

public sealed class WindowsSystemTrayService : IDisposable
{
    private const uint CallbackMessage = 0x8000 + 42;
    private const uint TrayIconId = 1;
    private const uint NimAdd = 0x00000000;
    private const uint NimDelete = 0x00000002;
    private const uint NifMessage = 0x00000001;
    private const uint NifIcon = 0x00000002;
    private const uint NifTip = 0x00000004;
    private const uint WmLButtonDoubleClick = 0x0203;
    private const uint WmRButtonUp = 0x0205;
    private const uint WmNull = 0x0000;
    private const uint MfString = 0x00000000;
    private const uint MfSeparator = 0x00000800;
    private const uint TpmRightButton = 0x0002;
    private const uint TpmReturnCommand = 0x0100;
    private const uint ImageIcon = 1;
    private const uint LrLoadFromFile = 0x0010;
    private const uint LrDefaultSize = 0x0040;
    private const uint OpenCommand = 1001;
    private const uint ExitCommand = 1002;

    private readonly nint window;
    private readonly Action openAction;
    private readonly Action exitAction;
    private readonly NativeMethods.SubclassProcedure subclassProcedure;
    private bool initialized;
    private bool disposed;

    public WindowsSystemTrayService(nint window, Action openAction, Action exitAction)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("系统托盘仅支持 Windows。");
        }
        if (window == nint.Zero)
        {
            throw new ArgumentException("主窗口句柄无效。", nameof(window));
        }

        this.window = window;
        this.openAction = openAction ?? throw new ArgumentNullException(nameof(openAction));
        this.exitAction = exitAction ?? throw new ArgumentNullException(nameof(exitAction));
        subclassProcedure = WindowProcedure;
    }

    public bool IsAvailable => initialized && !disposed;

    public void Initialize(string iconPath)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (initialized)
        {
            return;
        }
        ArgumentException.ThrowIfNullOrWhiteSpace(iconPath);

        nint icon = NativeMethods.LoadImage(
            nint.Zero, Path.GetFullPath(iconPath), ImageIcon, 0, 0,
            LrLoadFromFile | LrDefaultSize);
        if (icon == nint.Zero)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error(), "无法加载托盘图标。");
        }

        try
        {
            if (!NativeMethods.SetWindowSubclass(window, subclassProcedure, TrayIconId, 0))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "无法注册托盘窗口消息。");
            }

            NotifyIconData data = CreateNotifyIconData(icon);
            if (!NativeMethods.ShellNotifyIcon(NimAdd, ref data))
            {
                _ = NativeMethods.RemoveWindowSubclass(window, subclassProcedure, TrayIconId);
                throw new Win32Exception(Marshal.GetLastWin32Error(), "无法创建系统托盘图标。");
            }

            initialized = true;
        }
        finally
        {
            _ = NativeMethods.DestroyIcon(icon);
        }
    }

    private nint WindowProcedure(
        nint handle,
        uint message,
        nint wParam,
        nint lParam,
        nuint subclassId,
        nuint referenceData)
    {
        _ = wParam;
        _ = subclassId;
        _ = referenceData;
        if (message == CallbackMessage)
        {
            uint mouseMessage = unchecked((uint)lParam.ToInt64());
            if (mouseMessage == WmLButtonDoubleClick)
            {
                openAction();
                return nint.Zero;
            }
            if (mouseMessage == WmRButtonUp)
            {
                ShowContextMenu();
                return nint.Zero;
            }
        }

        return NativeMethods.DefSubclassProc(handle, message, wParam, lParam);
    }

    private void ShowContextMenu()
    {
        nint menu = NativeMethods.CreatePopupMenu();
        if (menu == nint.Zero)
        {
            return;
        }

        try
        {
            _ = NativeMethods.AppendMenu(menu, MfString, OpenCommand, "打开 FxTools");
            _ = NativeMethods.AppendMenu(menu, MfSeparator, 0, null);
            _ = NativeMethods.AppendMenu(menu, MfString, ExitCommand, "退出");
            _ = NativeMethods.GetCursorPos(out Point cursor);
            _ = NativeMethods.SetForegroundWindow(window);
            uint command = NativeMethods.TrackPopupMenuEx(
                menu, TpmRightButton | TpmReturnCommand, cursor.X, cursor.Y, window, nint.Zero);
            _ = NativeMethods.PostMessage(window, WmNull, nint.Zero, nint.Zero);
            if (command == OpenCommand)
            {
                openAction();
            }
            else if (command == ExitCommand)
            {
                exitAction();
            }
        }
        finally
        {
            _ = NativeMethods.DestroyMenu(menu);
        }
    }

    private NotifyIconData CreateNotifyIconData(nint icon) => new()
    {
        Size = checked((uint)Marshal.SizeOf<NotifyIconData>()),
        Window = window,
        Id = TrayIconId,
        Flags = NifMessage | NifIcon | NifTip,
        CallbackMessage = CallbackMessage,
        Icon = icon,
        Tip = "FxTools"
    };

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }
        disposed = true;
        if (!initialized)
        {
            return;
        }

        NotifyIconData data = CreateNotifyIconData(nint.Zero);
        data.Flags = 0;
        _ = NativeMethods.ShellNotifyIcon(NimDelete, ref data);
        _ = NativeMethods.RemoveWindowSubclass(window, subclassProcedure, TrayIconId);
        initialized = false;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Point
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyIconData
    {
        public uint Size;
        public nint Window;
        public uint Id;
        public uint Flags;
        public uint CallbackMessage;
        public nint Icon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string Tip;
        public uint State;
        public uint StateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string Info;
        public uint VersionOrTimeout;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string InfoTitle;
        public uint InfoFlags;
        public Guid ItemGuid;
        public nint BalloonIcon;
    }

    private static class NativeMethods
    {
        internal delegate nint SubclassProcedure(
            nint window,
            uint message,
            nint wParam,
            nint lParam,
            nuint subclassId,
            nuint referenceData);

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, EntryPoint = "Shell_NotifyIconW")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool ShellNotifyIcon(uint message, ref NotifyIconData data);

        [DllImport("comctl32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool SetWindowSubclass(
            nint window, SubclassProcedure procedure, nuint subclassId, nuint referenceData);

        [DllImport("comctl32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool RemoveWindowSubclass(
            nint window, SubclassProcedure procedure, nuint subclassId);

        [DllImport("comctl32.dll")]
        internal static extern nint DefSubclassProc(nint window, uint message, nint wParam, nint lParam);

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "LoadImageW")]
        internal static extern nint LoadImage(
            nint instance, string name, uint type, int width, int height, uint loadFlags);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool DestroyIcon(nint icon);

        [DllImport("user32.dll")]
        internal static extern nint CreatePopupMenu();

        [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "AppendMenuW")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool AppendMenu(nint menu, uint flags, nuint item, string? text);

        [DllImport("user32.dll")]
        internal static extern uint TrackPopupMenuEx(
            nint menu, uint flags, int x, int y, nint window, nint parameters);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool DestroyMenu(nint menu);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool GetCursorPos(out Point point);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool SetForegroundWindow(nint window);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool PostMessage(nint window, uint message, nint wParam, nint lParam);
    }
}
