using System.Diagnostics;
using FxTools.Core.Infrastructure;
using FxTools.Core.Windows;

namespace FxTools.Core.Services;

public sealed class JarProjectStore : IDisposable
{
    private readonly string path;
    private readonly AtomicJsonStore store;
    public JarProjectStore(string? path = null) { this.path = path ?? AppDataPaths.DataFile("jar_launcher_projects.json"); store = new(this.path); }
    public async Task<List<JarProject>> LoadAsync(CancellationToken cancellationToken = default)
    {
        List<JarProject> result = await store.LoadAsync(() => new List<JarProject>(), cancellationToken).ConfigureAwait(false); Validate(result); return result;
    }
    public async Task SaveAsync(IReadOnlyList<JarProject> projects, CancellationToken cancellationToken = default)
    {
        Validate(projects); if (File.Exists(path)) { _ = await LoadAsync(cancellationToken).ConfigureAwait(false); }
        await store.SaveAsync(projects, cancellationToken).ConfigureAwait(false);
    }
    public static void Validate(IReadOnlyList<JarProject> projects)
    {
        HashSet<int> ids = [];
        foreach (JarProject project in projects)
        {
            if (project is null || project.Id <= 0 || !ids.Add(project.Id) || string.IsNullOrWhiteSpace(project.Name)
                || string.IsNullOrWhiteSpace(project.SourceJar) || string.IsNullOrWhiteSpace(project.TargetJar)
                || !Path.IsPathFullyQualified(project.SourceJar) || !Path.IsPathFullyQualified(project.TargetJar)
                || string.Equals(Path.GetFullPath(project.SourceJar), Path.GetFullPath(project.TargetJar), StringComparison.OrdinalIgnoreCase)
                || project.DefaultPort is < 1 or > 65535)
            { throw new InvalidDataException("JAR 项目配置不符合当前格式。"); }
            bool sourceLib = !string.IsNullOrWhiteSpace(project.SourceLib); bool targetLib = !string.IsNullOrWhiteSpace(project.LibTarget);
            if (sourceLib != targetLib) { throw new InvalidDataException("源 Lib 与目标 Lib 必须同时填写。"); }
            if (sourceLib)
            {
                string source = Path.GetFullPath(project.SourceLib); string target = Path.GetFullPath(project.LibTarget);
                if (source.StartsWith(target, StringComparison.OrdinalIgnoreCase) || target.StartsWith(source, StringComparison.OrdinalIgnoreCase))
                { throw new InvalidDataException("源 Lib 与目标 Lib 不能相同或互相包含。"); }
            }
        }
    }
    public void Dispose() => store.Dispose();
}

public sealed class JarLauncherManager
{
    private const long MaxLogBytes = 50L * 1024 * 1024;
    private readonly Dictionary<int, JarSession> sessions = [];

    public static async Task CopyArtifactsAsync(JarProject project, CancellationToken cancellationToken = default)
    {
        JarProjectStore.Validate([project]);
        string sourceJar = Path.GetFullPath(project.SourceJar); string targetJar = Path.GetFullPath(project.TargetJar);
        if (!File.Exists(sourceJar)) { throw new FileNotFoundException("源 JAR 不存在。", sourceJar); }
        Directory.CreateDirectory(Path.GetDirectoryName(targetJar)!);
        string temporaryJar = $"{targetJar}.{Guid.NewGuid():N}.tmp";
        try
        {
            await CopyFileAsync(sourceJar, temporaryJar, cancellationToken).ConfigureAwait(false);
            File.Move(temporaryJar, targetJar, overwrite: true);
        }
        finally { File.Delete(temporaryJar); }
        if (!string.IsNullOrWhiteSpace(project.SourceLib))
        {
            await ReplaceDirectoryAsync(project.SourceLib, project.LibTarget, cancellationToken).ConfigureAwait(false);
        }
    }

    public async Task<JarSessionInfo> StartAsync(JarProject project, int port, string? profile, CancellationToken cancellationToken = default)
    {
        if (port is < 1 or > 65535) { throw new ArgumentOutOfRangeException(nameof(port)); }
        JarRunState state = CaptureState(project, port);
        if (state != JarRunState.Stopped) { throw new InvalidOperationException(state == JarRunState.Running ? "项目已在该端口运行。" : "端口被其他进程占用。"); }
        string target = Path.GetFullPath(project.TargetJar);
        if (!File.Exists(target)) { throw new FileNotFoundException("目标 JAR 不存在，请先复制。", target); }
        string java = FindJava();
        List<string> arguments = ["-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"];
        arguments.AddRange(WindowsDetachedProcessLauncher.ParseArguments(project.JvmOpts));
        arguments.Add("-jar"); arguments.Add(target); arguments.Add($"--server.port={port}");
        if (!string.IsNullOrWhiteSpace(profile)) { arguments.Add($"--spring.profiles.active={profile.Trim()}"); }
        arguments.AddRange(WindowsDetachedProcessLauncher.ParseArguments(project.OtherOpts));
        string log = ResolveLog(project, port); RotateLog(log);
        Process process = WindowsDetachedProcessLauncher.Start(java, arguments, Path.GetDirectoryName(target)!, log);
        using (process)
        {
            ProcessIdentity identity = new(process.Id, process.ProcessName, process.StartTime.ToUniversalTime());
            sessions[project.Id] = new(project.Id, port, identity, log);
            await Task.Yield(); cancellationToken.ThrowIfCancellationRequested();
            return new(project.Id, port, identity.ProcessId, log, identity.StartedAtUtc);
        }
    }

    public JarRunState CaptureState(JarProject project, int port)
    {
        ProcessPortSnapshot snapshot = ProcessPortService.Capture();
        int[] listeners = snapshot.Entries.Where(entry => entry.Protocol.StartsWith("TCP", StringComparison.Ordinal)
                                                         && entry.LocalPort == port && entry.State == "Listen")
            .Select(entry => entry.ProcessId).Distinct().ToArray();
        if (listeners.Length == 0)
        {
            if (sessions.TryGetValue(project.Id, out JarSession? starting) && IsAlive(starting.Identity)) { return JarRunState.Starting; }
            sessions.Remove(project.Id); return JarRunState.Stopped;
        }
        if (sessions.TryGetValue(project.Id, out JarSession? session) && listeners.Length == 1
            && listeners[0] == session.Identity.ProcessId && IsAlive(session.Identity)) { return JarRunState.Running; }
        return JarRunState.Occupied;
    }

    public async Task StopAsync(JarProject project, int port, CancellationToken cancellationToken = default)
    {
        if (!sessions.TryGetValue(project.Id, out JarSession? session) || !IsAlive(session.Identity))
        { throw new InvalidOperationException("没有可验证的本次启动进程，已拒绝终止。"); }
        ProcessPortSnapshot snapshot = await ProcessPortService.CaptureAsync(cancellationToken).ConfigureAwait(false);
        int[] listeners = snapshot.Entries.Where(entry => entry.Protocol.StartsWith("TCP", StringComparison.Ordinal)
                                                         && entry.LocalPort == port && entry.State == "Listen")
            .Select(entry => entry.ProcessId).Distinct().ToArray();
        if (listeners.Length > 0 && (listeners.Length != 1 || listeners[0] != session.Identity.ProcessId))
        { throw new InvalidOperationException("端口归属与本次启动 PID 不一致，已拒绝终止。"); }
        await ProcessPortService.TerminateAsync(session.Identity, true, cancellationToken).ConfigureAwait(false);
        sessions.Remove(project.Id);
    }

    public string? GetLogPath(int projectId) => sessions.TryGetValue(projectId, out JarSession? session) ? session.LogPath : null;

    public static string ResolveLogPath(JarProject project, int port) => ResolveLog(project, port);

    public static void OpenFolder(JarProject project)
    {
        string folder = Path.GetDirectoryName(Path.GetFullPath(project.TargetJar))
                        ?? throw new InvalidOperationException("目标 JAR 缺少父目录。");
        Directory.CreateDirectory(folder);
        _ = Process.Start(new ProcessStartInfo(folder) { UseShellExecute = true });
    }

    public static void OpenLog(JarProject project, int port)
    {
        string log = ResolveLog(project, port);
        if (!File.Exists(log)) { throw new FileNotFoundException("项目日志尚不存在。", log); }
        _ = Process.Start(new ProcessStartInfo(log) { UseShellExecute = true });
    }

    private static async Task ReplaceDirectoryAsync(string source, string target, CancellationToken cancellationToken)
    {
        source = Path.GetFullPath(source); target = Path.GetFullPath(target);
        if (!Directory.Exists(source)) { throw new DirectoryNotFoundException($"源 Lib 不存在: {source}"); }
        string temporary = $"{target}.{Guid.NewGuid():N}.tmp"; string backup = $"{target}.{Guid.NewGuid():N}.bak";
        Directory.CreateDirectory(temporary);
        try
        {
            foreach (string directory in Directory.EnumerateDirectories(source, "*", SearchOption.AllDirectories))
            { Directory.CreateDirectory(Path.Combine(temporary, Path.GetRelativePath(source, directory))); }
            foreach (string file in Directory.EnumerateFiles(source, "*", SearchOption.AllDirectories))
            {
                string destination = Path.Combine(temporary, Path.GetRelativePath(source, file));
                Directory.CreateDirectory(Path.GetDirectoryName(destination)!); await CopyFileAsync(file, destination, cancellationToken).ConfigureAwait(false);
            }
            if (Directory.Exists(target)) { Directory.Move(target, backup); }
            try { Directory.Move(temporary, target); }
            catch { if (Directory.Exists(backup) && !Directory.Exists(target)) { Directory.Move(backup, target); } throw; }
            if (Directory.Exists(backup)) { Directory.Delete(backup, recursive: true); }
        }
        finally { if (Directory.Exists(temporary)) { Directory.Delete(temporary, recursive: true); } }
    }

    private static async Task CopyFileAsync(string source, string target, CancellationToken cancellationToken)
    {
        await using FileStream input = new(source, FileMode.Open, FileAccess.Read, FileShare.Read, 128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        await using FileStream output = new(target, FileMode.Create, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.Asynchronous | FileOptions.WriteThrough);
        await input.CopyToAsync(output, 128 * 1024, cancellationToken).ConfigureAwait(false); await output.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    private static string FindJava()
    {
        string? home = Environment.GetEnvironmentVariable("JAVA_HOME")?.Trim().Trim('"');
        if (!string.IsNullOrEmpty(home)) { string candidate = Path.Combine(home, "bin", "java.exe"); if (File.Exists(candidate)) { return candidate; } }
        return DevEnvironmentService.FindExecutable("java") ?? throw new FileNotFoundException("未找到 java.exe，请配置 JAVA_HOME 或 PATH。");
    }
    private static string ResolveLog(JarProject project, int port) => Path.Combine(Path.GetDirectoryName(Path.GetFullPath(project.TargetJar))!, $"{Path.GetFileNameWithoutExtension(project.TargetJar)}-{port}.log");
    private static void RotateLog(string path)
    {
        if (File.Exists(path) && new FileInfo(path).Length >= MaxLogBytes) { File.Move(path, $"{path}.previous", overwrite: true); }
    }
    private static bool IsAlive(ProcessIdentity identity)
    {
        try { using Process process = Process.GetProcessById(identity.ProcessId); return process.ProcessName == identity.ProcessName && process.StartTime.ToUniversalTime() == identity.StartedAtUtc; }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception) { return false; }
    }
    private sealed record JarSession(int ProjectId, int Port, ProcessIdentity Identity, string LogPath);
}

public sealed class JarProject
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
    public string SourceJar { get; set; } = string.Empty;
    public string TargetJar { get; set; } = string.Empty;
    public string SourceLib { get; set; } = string.Empty;
    public string LibTarget { get; set; } = string.Empty;
    public int DefaultPort { get; set; } = 8080;
    public string DefaultProfile { get; set; } = string.Empty;
    public string JvmOpts { get; set; } = string.Empty;
    public string OtherOpts { get; set; } = string.Empty;
}
public sealed record JarSessionInfo(int ProjectId, int Port, int ProcessId, string LogPath, DateTime? StartedAtUtc);
public enum JarRunState { Stopped, Starting, Running, Occupied }
