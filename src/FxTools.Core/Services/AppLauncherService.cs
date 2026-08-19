using System.Collections.Concurrent;
using System.Diagnostics;
using FxTools.Core.Infrastructure;

namespace FxTools.Core.Services;

public sealed class AppLauncherStore : IDisposable
{
    private readonly string appPath;
    private readonly string settingsPath;
    private readonly AtomicJsonStore apps;
    private readonly AtomicJsonStore settings;

    public AppLauncherStore(string? appPath = null, string? settingsPath = null)
    {
        this.appPath = appPath ?? AppDataPaths.DataFile("app_launcher_paths.json");
        this.settingsPath = settingsPath ?? AppDataPaths.DataFile("app_launcher_settings.json");
        apps = new(this.appPath);
        settings = new(this.settingsPath);
    }

    public async Task<List<AppInfo>> LoadAppsAsync(CancellationToken cancellationToken = default)
    {
        List<AppInfo> result = await apps.LoadAsync(() => new List<AppInfo>(), cancellationToken).ConfigureAwait(false);
        ValidateApps(result);
        return result;
    }

    public async Task SaveAppsAsync(IReadOnlyList<AppInfo> values, CancellationToken cancellationToken = default)
    {
        ValidateApps(values);
        if (File.Exists(appPath)) { _ = await LoadAppsAsync(cancellationToken).ConfigureAwait(false); }
        await apps.SaveAsync(values, cancellationToken).ConfigureAwait(false);
    }

    public async Task<AppLauncherSettings> LoadSettingsAsync(CancellationToken cancellationToken = default)
    {
        AppLauncherSettings result = await settings.LoadAsync(() => new AppLauncherSettings(), cancellationToken).ConfigureAwait(false);
        ValidateSettings(result);
        return result;
    }

    public async Task SaveSettingsAsync(AppLauncherSettings value, CancellationToken cancellationToken = default)
    {
        ValidateSettings(value);
        if (File.Exists(settingsPath)) { _ = await LoadSettingsAsync(cancellationToken).ConfigureAwait(false); }
        await settings.SaveAsync(value, cancellationToken).ConfigureAwait(false);
    }

    private static void ValidateApps(IReadOnlyList<AppInfo> values)
    {
        HashSet<string> paths = new(StringComparer.OrdinalIgnoreCase);
        foreach (AppInfo value in values)
        {
            if (value is null || string.IsNullOrWhiteSpace(value.AppPath)
                || string.IsNullOrWhiteSpace(value.ProcessName) || !Path.IsPathFullyQualified(value.AppPath)
                || !paths.Add(Path.GetFullPath(value.AppPath)))
            {
                throw new InvalidDataException("启动项配置不符合当前格式。");
            }
        }
    }

    private static void ValidateSettings(AppLauncherSettings value)
    {
        if (value.LaunchDelayMillis is < 0 or > 60_000)
        {
            throw new InvalidDataException("启动间隔必须为 0 到 60 秒。");
        }
    }

    public void Dispose() { apps.Dispose(); settings.Dispose(); }
}

public sealed class AppLauncherManager
{
    private readonly ConcurrentDictionary<string, ProcessIdentity> managed = new(StringComparer.OrdinalIgnoreCase);

    public async Task<LaunchResult> LaunchAsync(AppInfo app, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(app);
        if (!File.Exists(app.AppPath)) { throw new FileNotFoundException("启动文件不存在。", app.AppPath); }
        string extension = Path.GetExtension(app.AppPath).ToLowerInvariant();
        ProcessStartInfo startInfo = new()
        {
            WorkingDirectory = Path.GetDirectoryName(app.AppPath) ?? Environment.CurrentDirectory,
            UseShellExecute = extension is not (".exe" or ".com"),
            FileName = app.AppPath
        };
        Process? process = Process.Start(startInfo);
        if (process is null) { throw new IOException("Windows 未返回启动进程。"); }
        using (process)
        {
            DateTime? started = SafeRead<DateTime?>(() => process.StartTime.ToUniversalTime(), null);
            ProcessIdentity identity = new(process.Id, SafeRead(() => process.ProcessName, app.ProcessName), started);
            managed[Path.GetFullPath(app.AppPath)] = identity;
            await Task.Yield();
            cancellationToken.ThrowIfCancellationRequested();
            return new(process.Id, identity.ProcessName, started);
        }
    }

    public async Task<IReadOnlyList<LaunchResult>> LaunchBatchAsync(
        IReadOnlyList<AppInfo> apps,
        TimeSpan delay,
        CancellationToken cancellationToken = default)
    {
        List<LaunchResult> results = [];
        for (int index = 0; index < apps.Count; index++)
        {
            results.Add(await LaunchAsync(apps[index], cancellationToken).ConfigureAwait(false));
            if (index < apps.Count - 1 && delay > TimeSpan.Zero)
            {
                await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
            }
        }
        return results;
    }

    public IReadOnlyList<AppRunState> CaptureStates(IReadOnlyList<AppInfo> apps)
    {
        Dictionary<string, List<ProcessIdentity>> processSnapshot = Process.GetProcesses()
            .Select(process =>
            {
                using (process)
                {
                    return new ProcessIdentity(process.Id, SafeRead(() => process.ProcessName, string.Empty),
                        SafeRead<DateTime?>(() => process.StartTime.ToUniversalTime(), null));
                }
            })
            .Where(identity => !string.IsNullOrEmpty(identity.ProcessName))
            .GroupBy(identity => NormalizeProcessName(identity.ProcessName), StringComparer.OrdinalIgnoreCase)
            .ToDictionary(group => group.Key, group => group.ToList(), StringComparer.OrdinalIgnoreCase);

        List<AppRunState> result = [];
        foreach (AppInfo app in apps)
        {
            string key = Path.GetFullPath(app.AppPath);
            if (managed.TryGetValue(key, out ProcessIdentity managedIdentity) && IdentityAlive(managedIdentity))
            {
                result.Add(new(app, AppProcessState.RunningManaged, [managedIdentity]));
                continue;
            }
            managed.TryRemove(key, out _);
            processSnapshot.TryGetValue(NormalizeProcessName(app.ProcessName), out List<ProcessIdentity>? matches);
            result.Add(new(app, matches?.Count switch
            {
                null or 0 => AppProcessState.Stopped,
                1 => AppProcessState.RunningExternal,
                _ => AppProcessState.Ambiguous
            }, matches ?? []));
        }
        return result;
    }

    public async Task StopAsync(AppRunState state, CancellationToken cancellationToken = default)
    {
        if (state.State == AppProcessState.Ambiguous || state.Processes.Count != 1)
        {
            throw new InvalidOperationException("未能唯一识别进程，已拒绝终止。");
        }
        await ProcessPortService.TerminateAsync(state.Processes[0], force: true, cancellationToken).ConfigureAwait(false);
        managed.TryRemove(Path.GetFullPath(state.App.AppPath), out _);
    }

    private static bool IdentityAlive(ProcessIdentity identity)
    {
        try
        {
            using Process process = Process.GetProcessById(identity.ProcessId);
            return string.Equals(process.ProcessName, identity.ProcessName, StringComparison.OrdinalIgnoreCase)
                   && (identity.StartedAtUtc is null || process.StartTime.ToUniversalTime() == identity.StartedAtUtc);
        }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return false;
        }
    }

    public static string InferProcessName(string path)
    {
        string launcher = Path.GetFileName(path).ToLowerInvariant();
        return launcher switch
        {
            "rabbitmq-server.bat" => "erl",
            "mysql.bat" => "mysqld",
            "redis-server.bat" => "redis-server",
            "startup.bat" or "catalina.bat" or "elasticsearch.bat" => "java",
            "npm.cmd" or "node.bat" => "node",
            _ => Path.GetFileNameWithoutExtension(path)
        };
    }

    private static string NormalizeProcessName(string name) => Path.GetFileNameWithoutExtension(name.Trim());
    private static T SafeRead<T>(Func<T> read, T fallback)
    {
        try { return read(); }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception or NotSupportedException) { return fallback; }
    }
}

public sealed class AppInfo
{
    public string AppPath { get; set; } = string.Empty;
    public string ProcessName { get; set; } = string.Empty;
}

public sealed class AppLauncherSettings { public int LaunchDelayMillis { get; set; } = 1000; }
public sealed record LaunchResult(int ProcessId, string ProcessName, DateTime? StartedAtUtc);
public sealed record AppRunState(AppInfo App, AppProcessState State, IReadOnlyList<ProcessIdentity> Processes);
public enum AppProcessState { Stopped, RunningManaged, RunningExternal, Ambiguous }
