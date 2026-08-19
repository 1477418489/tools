using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Text;
using System.Xml.Linq;
using Microsoft.Win32;

namespace FxTools.Core.Services;

[SupportedOSPlatform("windows")]
public static partial class WindowsPowerService
{
    public const string PowerTaskName = "FxTools-PowerAction";
    public const string WakeTaskName = "FxTools-Wake";
    private const string TaskNamespace = "http://schemas.microsoft.com/windows/2004/02/mit/task";

    public static async Task SchedulePowerAsync(
        DateTime scheduledFor,
        PowerAction action,
        CancellationToken cancellationToken = default)
    {
        RequireFuture(scheduledFor);
        string arguments = action switch
        {
            PowerAction.Shutdown => "/s /t 60 /c \"FxTools scheduled shutdown\"",
            PowerAction.Restart => "/r /t 60 /c \"FxTools scheduled restart\"",
            PowerAction.Hibernate => "/h",
            _ => throw new ArgumentOutOfRangeException(nameof(action))
        };
        string executable = Path.Combine(Environment.SystemDirectory, "shutdown.exe");
        await RegisterTaskAsync(PowerTaskName, scheduledFor, executable, arguments, wake: false, cancellationToken)
            .ConfigureAwait(false);
    }

    public static Task ScheduleWakeAsync(DateTime scheduledFor, CancellationToken cancellationToken = default)
    {
        RequireFuture(scheduledFor);
        return RegisterTaskAsync(WakeTaskName, scheduledFor,
            Path.Combine(Environment.SystemDirectory, "cmd.exe"), "/d /c exit 0", wake: true,
            cancellationToken);
    }

    public static async Task CancelPowerAsync(CancellationToken cancellationToken = default)
    {
        await DeleteTaskAsync(PowerTaskName, cancellationToken).ConfigureAwait(false);
        try
        {
            _ = await BoundedCommandRunner.RunAsync(Path.Combine(Environment.SystemDirectory, "shutdown.exe"),
                ["/a"], TimeSpan.FromSeconds(5), 4096, cancellationToken).ConfigureAwait(false);
        }
        catch (IOException) { }
    }

    public static Task CancelWakeAsync(CancellationToken cancellationToken = default) =>
        DeleteTaskAsync(WakeTaskName, cancellationToken);

    public static async Task<ScheduledPowerTask> QueryTaskAsync(
        string taskName,
        CancellationToken cancellationToken = default)
    {
        CommandResult result = await BoundedCommandRunner.RunAsync(
            Path.Combine(Environment.SystemDirectory, "schtasks.exe"),
            ["/Query", "/TN", taskName, "/XML"], TimeSpan.FromSeconds(10), 256 * 1024,
            cancellationToken).ConfigureAwait(false);
        if (!result.Successful) { return new(false, null, null, "不存在"); }
        try
        {
            XDocument document = XDocument.Parse(result.Output);
            XNamespace ns = TaskNamespace;
            DateTime? next = DateTime.TryParse(document.Descendants(ns + "StartBoundary").FirstOrDefault()?.Value,
                out DateTime parsed) ? parsed : null;
            string arguments = document.Descendants(ns + "Arguments").FirstOrDefault()?.Value ?? string.Empty;
            PowerAction? action = arguments.Contains("/s ", StringComparison.OrdinalIgnoreCase) ? PowerAction.Shutdown
                : arguments.Contains("/r ", StringComparison.OrdinalIgnoreCase) ? PowerAction.Restart
                : arguments.Contains("/h", StringComparison.OrdinalIgnoreCase) ? PowerAction.Hibernate : null;
            return new(true, next, action, "已计划");
        }
        catch (System.Xml.XmlException exception)
        {
            throw new IOException("无法解析任务计划状态。", exception);
        }
    }

    public static async Task ExecuteImmediateAsync(PowerAction action, CancellationToken cancellationToken = default)
    {
        if (action == PowerAction.Sleep)
        {
            if (!NativeMethods.SetSuspendState(false, true, false)) { throw new Win32Exception(Marshal.GetLastWin32Error()); }
            return;
        }
        string argument = action switch
        {
            PowerAction.Shutdown => "/s",
            PowerAction.Restart => "/r",
            PowerAction.Hibernate => "/h",
            _ => throw new ArgumentOutOfRangeException(nameof(action))
        };
        IReadOnlyList<string> arguments = action == PowerAction.Hibernate
            ? [argument] : [argument, "/t", "0"];
        CommandResult result = await BoundedCommandRunner.RunAsync(
            Path.Combine(Environment.SystemDirectory, "shutdown.exe"),
            arguments, TimeSpan.FromSeconds(5), 4096, cancellationToken).ConfigureAwait(false);
        if (!result.Successful) { throw new IOException(result.Output); }
    }

    public static async Task<PowerDiagnostics> InspectAsync(CancellationToken cancellationToken = default)
    {
        StringBuilder details = new();
        string manufacturer = RegistryValue(@"HARDWARE\DESCRIPTION\System\BIOS", "SystemManufacturer");
        string model = RegistryValue(@"HARDWARE\DESCRIPTION\System\BIOS", "SystemProductName");
        string biosVendor = RegistryValue(@"HARDWARE\DESCRIPTION\System\BIOS", "BIOSVendor");
        string biosVersion = RegistryValue(@"HARDWARE\DESCRIPTION\System\BIOS", "BIOSVersion");
        string biosDate = RegistryValue(@"HARDWARE\DESCRIPTION\System\BIOS", "BIOSReleaseDate");
        NativeMethods.GetFirmwareType(out uint firmwareType);
        string supply = "供电状态未知";
        if (NativeMethods.GetSystemPowerStatus(out SystemPowerStatus power))
        {
            string source = power.AcLineStatus == 1 ? "交流供电" : power.AcLineStatus == 0 ? "电池供电" : "供电来源未知";
            supply = power.BatteryFlag == 255 || (power.BatteryFlag & 128) != 0
                ? $"{source} · 未检测到电池"
                : $"{source} · {(power.BatteryLifePercent == 255 ? "电量未知" : $"{power.BatteryLifePercent}%")}";
        }
        foreach ((string name, string[] arguments) in new[]
                 {
                     ("电源状态", new[] { "/a" }),
                     ("活动电源计划", new[] { "/getactivescheme" }),
                     ("可唤醒设备", new[] { "/devicequery", "wake_armed" }),
                     ("唤醒计时器", new[] { "/waketimers" })
                 })
        {
            try
            {
                CommandResult result = await BoundedCommandRunner.RunAsync(
                    Path.Combine(Environment.SystemDirectory, "powercfg.exe"), arguments,
                    TimeSpan.FromSeconds(10), 128 * 1024, cancellationToken).ConfigureAwait(false);
                details.Append('[').Append(name).AppendLine("]").AppendLine(result.Output).AppendLine();
            }
            catch (Exception exception) when (exception is IOException or TimeoutException)
            {
                details.Append('[').Append(name).Append("] ").AppendLine(exception.Message).AppendLine();
            }
        }
        return new(manufacturer, model, Environment.OSVersion.VersionString,
            biosVendor, biosVersion, biosDate, firmwareType == 2 ? "UEFI" : firmwareType == 1 ? "传统 BIOS" : "未知",
            supply, details.ToString().Trim());
    }

    private static async Task RegisterTaskAsync(
        string name, DateTime scheduledFor, string executable, string arguments,
        bool wake, CancellationToken cancellationToken)
    {
        string temporary = Path.Combine(Path.GetTempPath(), $"FxTools.Task.{Guid.NewGuid():N}.xml");
        try
        {
            await Infrastructure.AtomicFileWriter.WriteUtf8Async(
                temporary, BuildTaskXml(scheduledFor, executable, arguments, wake), cancellationToken).ConfigureAwait(false);
            CommandResult result = await BoundedCommandRunner.RunAsync(
                Path.Combine(Environment.SystemDirectory, "schtasks.exe"),
                ["/Create", "/TN", name, "/XML", temporary, "/F"], TimeSpan.FromSeconds(20),
                64 * 1024, cancellationToken).ConfigureAwait(false);
            if (!result.Successful) { throw new IOException($"创建任务计划失败: {result.Output}"); }
        }
        finally { File.Delete(temporary); }
    }

    private static async Task DeleteTaskAsync(string name, CancellationToken cancellationToken)
    {
        CommandResult result = await BoundedCommandRunner.RunAsync(
            Path.Combine(Environment.SystemDirectory, "schtasks.exe"),
            ["/Delete", "/TN", name, "/F"], TimeSpan.FromSeconds(10), 32 * 1024,
            cancellationToken).ConfigureAwait(false);
        if (!result.Successful && !result.Output.Contains("cannot find", StringComparison.OrdinalIgnoreCase))
        {
            // schtasks uses exit code 1 both when a task is missing and for localized errors.
            ScheduledPowerTask status = await QueryTaskAsync(name, cancellationToken).ConfigureAwait(false);
            if (status.Exists) { throw new IOException($"删除任务计划失败: {result.Output}"); }
        }
    }

    private static string BuildTaskXml(DateTime time, string executable, string arguments, bool wake)
    {
        XNamespace ns = TaskNamespace;
        XDocument document = new(new XDeclaration("1.0", "utf-8", null),
            new XElement(ns + "Task", new XAttribute("version", "1.4"),
                new XElement(ns + "RegistrationInfo", new XElement(ns + "Description", "FxTools scheduled task")),
                new XElement(ns + "Triggers", new XElement(ns + "TimeTrigger",
                    new XElement(ns + "StartBoundary", time.ToString("yyyy-MM-dd'T'HH:mm:ss", System.Globalization.CultureInfo.InvariantCulture)),
                    new XElement(ns + "Enabled", "true"))),
                new XElement(ns + "Principals", new XElement(ns + "Principal", new XAttribute("id", "Author"),
                    new XElement(ns + "LogonType", "InteractiveToken"), new XElement(ns + "RunLevel", "LeastPrivilege"))),
                new XElement(ns + "Settings", new XElement(ns + "MultipleInstancesPolicy", "IgnoreNew"),
                    new XElement(ns + "DisallowStartIfOnBatteries", "false"), new XElement(ns + "StopIfGoingOnBatteries", "false"),
                    new XElement(ns + "StartWhenAvailable", "true"), new XElement(ns + "WakeToRun", wake.ToString().ToLowerInvariant()),
                    new XElement(ns + "Enabled", "true"), new XElement(ns + "ExecutionTimeLimit", "PT5M")),
                new XElement(ns + "Actions", new XAttribute("Context", "Author"), new XElement(ns + "Exec",
                    new XElement(ns + "Command", executable), new XElement(ns + "Arguments", arguments)))));
        return document.ToString();
    }

    private static string RegistryValue(string key, string name)
    {
        using RegistryKey? registry = Registry.LocalMachine.OpenSubKey(key);
        object? value = registry?.GetValue(name);
        return value is string[] values ? string.Join(" ", values) : value?.ToString()?.Trim() ?? string.Empty;
    }

    private static void RequireFuture(DateTime time)
    {
        if (time <= DateTime.Now) { throw new ArgumentException("计划时间必须晚于当前时间。", nameof(time)); }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SystemPowerStatus
    {
        public byte AcLineStatus; public byte BatteryFlag; public byte BatteryLifePercent; public byte SystemStatusFlag;
        public int BatteryLifeTime; public int BatteryFullLifeTime;
    }
    private static partial class NativeMethods
    {
        [LibraryImport("kernel32.dll")][return: MarshalAs(UnmanagedType.Bool)] internal static partial bool GetSystemPowerStatus(out SystemPowerStatus status);
        [LibraryImport("kernel32.dll")][return: MarshalAs(UnmanagedType.Bool)] internal static partial bool GetFirmwareType(out uint firmwareType);
        [LibraryImport("powrprof.dll", SetLastError = true)][return: MarshalAs(UnmanagedType.Bool)] internal static partial bool SetSuspendState([MarshalAs(UnmanagedType.Bool)] bool hibernate, [MarshalAs(UnmanagedType.Bool)] bool force, [MarshalAs(UnmanagedType.Bool)] bool disableWakeEvent);
    }
}

public sealed record ScheduledPowerTask(bool Exists, DateTime? ScheduledFor, PowerAction? Action, string State);
public sealed record PowerDiagnostics(string Manufacturer, string Model, string OperatingSystem,
    string BiosVendor, string BiosVersion, string BiosDate, string FirmwareType, string PowerSupply, string Details);
public enum PowerAction { Shutdown, Restart, Hibernate, Sleep }
