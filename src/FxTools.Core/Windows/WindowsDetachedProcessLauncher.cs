using System.Collections;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace FxTools.Core.Windows;

public static class WindowsDetachedProcessLauncher
{
    private const uint FileAppendData = 0x00000004;
    private const uint GenericRead = 0x80000000;
    private const uint FileShareRead = 0x00000001;
    private const uint FileShareWrite = 0x00000002;
    private const uint FileShareDelete = 0x00000004;
    private const uint OpenAlways = 4;
    private const uint OpenExisting = 3;
    private const uint FileAttributeNormal = 0x80;
    private const uint StartfUseStdHandles = 0x00000100;
    private const uint CreateNoWindow = 0x08000000;
    private const uint CreateUnicodeEnvironment = 0x00000400;

    public static Process Start(
        string executable,
        IReadOnlyList<string> arguments,
        string workingDirectory,
        string outputLog)
    {
        if (!OperatingSystem.IsWindows()) { throw new PlatformNotSupportedException(); }
        ArgumentException.ThrowIfNullOrWhiteSpace(executable);
        Directory.CreateDirectory(Path.GetDirectoryName(outputLog)
                                  ?? throw new IOException("输出日志缺少父目录。"));
        SecurityAttributes security = new()
        {
            Length = Marshal.SizeOf<SecurityAttributes>(),
            InheritHandle = true
        };
        using SafeFileHandle log = NativeMethods.CreateFile(
            outputLog, FileAppendData, FileShareRead | FileShareWrite | FileShareDelete,
            ref security, OpenAlways, FileAttributeNormal, nint.Zero);
        using SafeFileHandle input = NativeMethods.CreateFile(
            "NUL", GenericRead, FileShareRead | FileShareWrite, ref security,
            OpenExisting, FileAttributeNormal, nint.Zero);
        if (log.IsInvalid || input.IsInvalid)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error(), "无法创建 JAR 进程日志句柄。");
        }

        StartupInfo startup = new()
        {
            Size = Marshal.SizeOf<StartupInfo>(),
            Flags = StartfUseStdHandles,
            StandardInput = input.DangerousGetHandle(),
            StandardOutput = log.DangerousGetHandle(),
            StandardError = log.DangerousGetHandle()
        };
        string command = string.Join(' ', new[] { Quote(executable) }.Concat(arguments.Select(Quote)));
        char[] mutableCommand = string.Concat(command, '\0').ToCharArray();
        nint environment = BuildEnvironmentBlock();
        try
        {
            if (!NativeMethods.CreateProcess(
                    executable, mutableCommand, nint.Zero, nint.Zero, true,
                    CreateNoWindow | CreateUnicodeEnvironment, environment,
                    workingDirectory, ref startup, out ProcessInformation information))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), $"无法启动 {executable}");
            }
            try { return Process.GetProcessById(checked((int)information.ProcessId)); }
            finally
            {
                _ = NativeMethods.CloseHandle(information.Thread);
                _ = NativeMethods.CloseHandle(information.Process);
            }
        }
        finally { Marshal.FreeHGlobal(environment); }
    }

    public static string[] ParseArguments(string? commandLine)
    {
        if (string.IsNullOrWhiteSpace(commandLine)) { return []; }
        nint arguments = NativeMethods.CommandLineToArgvW($"fxtools {commandLine}", out int count);
        if (arguments == nint.Zero) { throw new Win32Exception(Marshal.GetLastWin32Error()); }
        try
        {
            string[] result = new string[Math.Max(0, count - 1)];
            for (int index = 1; index < count; index++)
            {
                nint pointer = Marshal.ReadIntPtr(arguments, index * nint.Size);
                result[index - 1] = Marshal.PtrToStringUni(pointer) ?? string.Empty;
            }
            return result;
        }
        finally { _ = NativeMethods.LocalFree(arguments); }
    }

    private static string Quote(string value)
    {
        if (value.Length > 0 && !value.Any(character => char.IsWhiteSpace(character) || character == '"')) { return value; }
        StringBuilder result = new(value.Length + 2); result.Append('"'); int slashes = 0;
        foreach (char character in value)
        {
            if (character == '\\') { slashes++; continue; }
            if (character == '"') { result.Append('\\', slashes * 2 + 1).Append('"'); slashes = 0; continue; }
            result.Append('\\', slashes).Append(character); slashes = 0;
        }
        result.Append('\\', slashes * 2).Append('"'); return result.ToString();
    }

    private static nint BuildEnvironmentBlock()
    {
        string[] excluded = ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"];
        SortedDictionary<string, string> values = new(StringComparer.OrdinalIgnoreCase);
        foreach (DictionaryEntry entry in Environment.GetEnvironmentVariables())
        {
            string key = entry.Key.ToString() ?? string.Empty;
            if (!excluded.Contains(key, StringComparer.OrdinalIgnoreCase)) { values[key] = entry.Value?.ToString() ?? string.Empty; }
        }
        string block = string.Join('\0', values.Select(pair => $"{pair.Key}={pair.Value}")) + "\0\0";
        return Marshal.StringToHGlobalUni(block);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes { public int Length; public nint SecurityDescriptor; [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo
    {
        public int Size; public string? Reserved; public string? Desktop; public string? Title;
        public int X; public int Y; public int XSize; public int YSize; public int XCountChars; public int YCountChars;
        public int FillAttribute; public uint Flags; public short ShowWindow; public short Reserved2; public nint Reserved2Pointer;
        public nint StandardInput; public nint StandardOutput; public nint StandardError;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInformation { public nint Process; public nint Thread; public uint ProcessId; public uint ThreadId; }

    private static class NativeMethods
    {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        internal static extern SafeFileHandle CreateFile(string name, uint access, uint share, ref SecurityAttributes security, uint creation, uint attributes, nint template);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool CreateProcess(string? application, char[] commandLine, nint processAttributes, nint threadAttributes,
            [MarshalAs(UnmanagedType.Bool)] bool inheritHandles, uint flags, nint environment, string currentDirectory,
            ref StartupInfo startupInfo, out ProcessInformation processInformation);
        [DllImport("kernel32.dll")][return: MarshalAs(UnmanagedType.Bool)] internal static extern bool CloseHandle(nint handle);
        [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)] internal static extern nint CommandLineToArgvW(string commandLine, out int argumentCount);
        [DllImport("kernel32.dll")] internal static extern nint LocalFree(nint memory);
    }
}
