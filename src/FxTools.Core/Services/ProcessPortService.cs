using System.ComponentModel;
using System.Diagnostics;
using System.Net;
using System.Runtime.InteropServices;

namespace FxTools.Core.Services;

public static class ProcessPortService
{
    public static Task<ProcessPortSnapshot> CaptureAsync(CancellationToken cancellationToken = default) =>
        Task.Run(() => Capture(cancellationToken), cancellationToken);

    public static ProcessPortSnapshot Capture(CancellationToken cancellationToken = default)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("进程与端口快照仅支持 Windows。");
        }

        Dictionary<int, ProcessInfo> processes = CaptureProcesses(cancellationToken);
        List<PortBinding> bindings = [];
        bindings.AddRange(IpHelperTables.ReadTcp(NativeAddressFamily.InterNetwork));
        cancellationToken.ThrowIfCancellationRequested();
        bindings.AddRange(IpHelperTables.ReadTcp(NativeAddressFamily.InterNetworkV6));
        cancellationToken.ThrowIfCancellationRequested();
        bindings.AddRange(IpHelperTables.ReadUdp(NativeAddressFamily.InterNetwork));
        bindings.AddRange(IpHelperTables.ReadUdp(NativeAddressFamily.InterNetworkV6));

        List<ProcessPortEntry> entries = new(bindings.Count);
        foreach (PortBinding binding in bindings)
        {
            cancellationToken.ThrowIfCancellationRequested();
            processes.TryGetValue(binding.ProcessId, out ProcessInfo? process);
            entries.Add(new(
                binding.Protocol,
                binding.LocalAddress,
                binding.LocalPort,
                binding.RemoteAddress,
                binding.RemotePort,
                binding.State,
                binding.ProcessId,
                process?.Name ?? "未知进程",
                process?.ExecutablePath ?? string.Empty,
                process?.WindowTitle ?? string.Empty,
                process?.WorkingSetBytes,
                process?.StartedAtUtc));
        }

        entries.Sort(static (left, right) =>
        {
            int port = left.LocalPort.CompareTo(right.LocalPort);
            return port != 0 ? port : left.ProcessId.CompareTo(right.ProcessId);
        });
        return new(DateTime.UtcNow, processes.Values.OrderBy(process => process.Name).ToArray(), entries);
    }

    public static async Task TerminateAsync(
        ProcessIdentity identity,
        bool force,
        CancellationToken cancellationToken = default)
    {
        if (identity.ProcessId is <= 4 || identity.ProcessId == Environment.ProcessId)
        {
            throw new InvalidOperationException("拒绝终止系统关键进程或 FxTools 自身。");
        }

        using Process process = Process.GetProcessById(identity.ProcessId);
        string actualName = SafeRead(() => process.ProcessName, string.Empty);
        DateTime? actualStart = SafeRead<DateTime?>(() => process.StartTime.ToUniversalTime(), null);
        if (!string.Equals(actualName, identity.ProcessName, StringComparison.OrdinalIgnoreCase)
            || identity.StartedAtUtc is not null && actualStart != identity.StartedAtUtc)
        {
            throw new InvalidOperationException("进程身份已变化，可能发生 PID 复用，请刷新后重试。");
        }

        bool closeRequested = !force && SafeRead(process.CloseMainWindow, false);
        if (closeRequested)
        {
            try
            {
                await process.WaitForExitAsync(cancellationToken)
                    .WaitAsync(TimeSpan.FromSeconds(3), cancellationToken).ConfigureAwait(false);
                return;
            }
            catch (TimeoutException)
            {
            }
        }

        process.Kill(entireProcessTree: force);
        await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
    }

    private static Dictionary<int, ProcessInfo> CaptureProcesses(CancellationToken cancellationToken)
    {
        Dictionary<int, ProcessInfo> result = [];
        foreach (Process process in Process.GetProcesses())
        {
            using (process)
            {
                cancellationToken.ThrowIfCancellationRequested();
                int processId = SafeRead(() => process.Id, -1);
                if (processId < 0)
                {
                    continue;
                }
                string name = SafeRead(() => process.ProcessName, "未知进程");
                result[processId] = new(
                    processId,
                    name,
                    SafeRead(() => process.MainModule?.FileName ?? string.Empty, string.Empty),
                    SafeRead(() => process.MainWindowTitle, string.Empty),
                    SafeRead<long?>(() => process.WorkingSet64, null),
                    SafeRead<DateTime?>(() => process.StartTime.ToUniversalTime(), null));
            }
        }
        return result;
    }

    private static T SafeRead<T>(Func<T> getter, T fallback)
    {
        try { return getter(); }
        catch (Exception exception) when (exception is InvalidOperationException
                                          or Win32Exception
                                          or NotSupportedException)
        {
            return fallback;
        }
    }
}

public sealed record ProcessPortSnapshot(
    DateTime CapturedAtUtc,
    IReadOnlyList<ProcessInfo> Processes,
    IReadOnlyList<ProcessPortEntry> Entries);

public sealed record ProcessInfo(
    int ProcessId,
    string Name,
    string ExecutablePath,
    string WindowTitle,
    long? WorkingSetBytes,
    DateTime? StartedAtUtc)
{
    public ProcessIdentity Identity => new(ProcessId, Name, StartedAtUtc);
}

public readonly record struct ProcessIdentity(int ProcessId, string ProcessName, DateTime? StartedAtUtc);

public sealed record ProcessPortEntry(
    string Protocol,
    string LocalAddress,
    int LocalPort,
    string RemoteAddress,
    int? RemotePort,
    string State,
    int ProcessId,
    string ProcessName,
    string ExecutablePath,
    string WindowTitle,
    long? WorkingSetBytes,
    DateTime? StartedAtUtc);

internal enum NativeAddressFamily : int
{
    InterNetwork = 2,
    InterNetworkV6 = 23
}

internal readonly record struct PortBinding(
    string Protocol,
    string LocalAddress,
    int LocalPort,
    string RemoteAddress,
    int? RemotePort,
    string State,
    int ProcessId);

internal static partial class IpHelperTables
{
    private const uint ErrorInsufficientBuffer = 122;

    public static IReadOnlyList<PortBinding> ReadTcp(NativeAddressFamily family)
    {
        using NativeTable table = NativeTable.Load(
            (nint buffer, ref int size) => NativeMethods.GetExtendedTcpTable(
                buffer, ref size, false, (int)family, TcpTableClass.OwnerPidAll, 0));
        return family == NativeAddressFamily.InterNetwork
            ? ReadRows<MibTcpRowOwnerPid>(table, row => new(
                "TCP",
                new IPAddress(BitConverter.GetBytes(row.LocalAddress)).ToString(),
                DecodePort(row.LocalPort),
                new IPAddress(BitConverter.GetBytes(row.RemoteAddress)).ToString(),
                DecodePort(row.RemotePort),
                ((TcpState)row.State).ToString(),
                unchecked((int)row.OwningProcessId)))
            : ReadRows<MibTcp6RowOwnerPid>(table, row => new(
                "TCP6",
                new IPAddress(row.LocalAddress, row.LocalScopeId).ToString(),
                DecodePort(row.LocalPort),
                new IPAddress(row.RemoteAddress, row.RemoteScopeId).ToString(),
                DecodePort(row.RemotePort),
                ((TcpState)row.State).ToString(),
                unchecked((int)row.OwningProcessId)));
    }

    public static IReadOnlyList<PortBinding> ReadUdp(NativeAddressFamily family)
    {
        using NativeTable table = NativeTable.Load(
            (nint buffer, ref int size) => NativeMethods.GetExtendedUdpTable(
                buffer, ref size, false, (int)family, UdpTableClass.OwnerPid, 0));
        return family == NativeAddressFamily.InterNetwork
            ? ReadRows<MibUdpRowOwnerPid>(table, row => new(
                "UDP",
                new IPAddress(BitConverter.GetBytes(row.LocalAddress)).ToString(),
                DecodePort(row.LocalPort),
                string.Empty,
                null,
                "Listening",
                unchecked((int)row.OwningProcessId)))
            : ReadRows<MibUdp6RowOwnerPid>(table, row => new(
                "UDP6",
                new IPAddress(row.LocalAddress, row.LocalScopeId).ToString(),
                DecodePort(row.LocalPort),
                string.Empty,
                null,
                "Listening",
                unchecked((int)row.OwningProcessId)));
    }

    private static List<PortBinding> ReadRows<TRow>(
        NativeTable table,
        Func<TRow, PortBinding> convert) where TRow : struct
    {
        uint count = unchecked((uint)Marshal.ReadInt32(table.Pointer));
        int rowSize = Marshal.SizeOf<TRow>();
        nint rowPointer = table.Pointer + sizeof(uint);
        List<PortBinding> result = new(checked((int)Math.Min(count, 100_000)));
        for (uint index = 0; index < count; index++)
        {
            TRow row = Marshal.PtrToStructure<TRow>(rowPointer + checked((int)(index * rowSize)));
            result.Add(convert(row));
        }
        return result;
    }

    private static int DecodePort(uint value) =>
        (int)(((value & 0xFF) << 8) | ((value >> 8) & 0xFF));

    private delegate uint TableLoader(nint buffer, ref int size);

    private sealed class NativeTable : IDisposable
    {
        private NativeTable(nint pointer) => Pointer = pointer;

        public nint Pointer { get; }

        public static NativeTable Load(TableLoader loader)
        {
            int size = 0;
            uint first = loader(nint.Zero, ref size);
            if (first != ErrorInsufficientBuffer || size <= 0)
            {
                throw new Win32Exception(unchecked((int)first));
            }
            nint buffer = Marshal.AllocHGlobal(size);
            uint result = loader(buffer, ref size);
            if (result != 0)
            {
                Marshal.FreeHGlobal(buffer);
                throw new Win32Exception(unchecked((int)result));
            }
            return new(buffer);
        }

        public void Dispose() => Marshal.FreeHGlobal(Pointer);
    }

    private enum TcpTableClass
    {
        OwnerPidAll = 5
    }

    private enum UdpTableClass
    {
        OwnerPid = 1
    }

    private enum TcpState : uint
    {
        Closed = 1,
        Listen = 2,
        SynSent = 3,
        SynReceived = 4,
        Established = 5,
        FinWait1 = 6,
        FinWait2 = 7,
        CloseWait = 8,
        Closing = 9,
        LastAck = 10,
        TimeWait = 11,
        DeleteTcb = 12
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MibTcpRowOwnerPid
    {
        public uint State;
        public uint LocalAddress;
        public uint LocalPort;
        public uint RemoteAddress;
        public uint RemotePort;
        public uint OwningProcessId;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MibTcp6RowOwnerPid
    {
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] LocalAddress;
        public uint LocalScopeId;
        public uint LocalPort;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] RemoteAddress;
        public uint RemoteScopeId;
        public uint RemotePort;
        public uint State;
        public uint OwningProcessId;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MibUdpRowOwnerPid
    {
        public uint LocalAddress;
        public uint LocalPort;
        public uint OwningProcessId;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MibUdp6RowOwnerPid
    {
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] LocalAddress;
        public uint LocalScopeId;
        public uint LocalPort;
        public uint OwningProcessId;
    }

    private static partial class NativeMethods
    {
        [LibraryImport("iphlpapi.dll")]
        internal static partial uint GetExtendedTcpTable(
            nint table,
            ref int size,
            [MarshalAs(UnmanagedType.Bool)] bool order,
            int addressFamily,
            TcpTableClass tableClass,
            uint reserved);

        [LibraryImport("iphlpapi.dll")]
        internal static partial uint GetExtendedUdpTable(
            nint table,
            ref int size,
            [MarshalAs(UnmanagedType.Bool)] bool order,
            int addressFamily,
            UdpTableClass tableClass,
            uint reserved);
    }
}
