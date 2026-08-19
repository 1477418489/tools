using System.Runtime.InteropServices;

namespace FxTools.Core.Services;

public static class DevEnvironmentService
{
    private static readonly ToolDefinition[] Tools =
    [
        new("jdk", "JDK 编译器", "javac", ["-version"], true),
        new("maven", "Apache Maven", "mvn", ["--version"], true),
        new("git", "Git", "git", ["--version"], true),
        new("node", "Node.js", "node", ["--version"], false),
        new("npm", "npm", "npm", ["--version"], false),
        new("python", "Python", "python", ["--version"], false),
        new("docker", "Docker CLI", "docker", ["--version"], false)
    ];

    public static async Task<EnvironmentReport> InspectAsync(CancellationToken cancellationToken = default)
    {
        Task<CheckResult>[] checks = Tools.Select(
            tool => InspectToolAsync(tool, cancellationToken)).ToArray();
        CheckResult[] toolResults = await Task.WhenAll(checks).ConfigureAwait(false);
        List<CheckResult> results = [RuntimeResult(), .. toolResults];
        return new(
            results,
            results.Count(result => result.Status == CheckStatus.Available),
            results.Count(result => result.Recommended && result.Status != CheckStatus.Available));
    }

    public static string? FindExecutable(string command)
    {
        if (string.IsNullOrWhiteSpace(command))
        {
            return null;
        }
        string? pathValue = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrWhiteSpace(pathValue))
        {
            return null;
        }

        string[] extensions = GetExecutableExtensions();
        foreach (string rawDirectory in pathValue.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            string directory = rawDirectory.Trim().Trim('"');
            foreach (string extension in extensions)
            {
                try
                {
                    string candidate = Path.Combine(directory, command + extension);
                    if (File.Exists(candidate))
                    {
                        return Path.GetFullPath(candidate);
                    }
                }
                catch (Exception exception) when (exception is ArgumentException or NotSupportedException or PathTooLongException)
                {
                }
            }
        }
        return null;
    }

    private static async Task<CheckResult> InspectToolAsync(
        ToolDefinition tool,
        CancellationToken cancellationToken)
    {
        string? executable = FindExecutable(tool.Command);
        if (executable is null && tool.Id == "python")
        {
            executable = FindExecutable("py");
        }
        if (executable is null)
        {
            return new(tool.Id, tool.Name, CheckStatus.Missing, "未发现", string.Empty,
                tool.Recommended ? "未在 PATH 中找到，建议配置后重新检测" : "未安装或未加入 PATH",
                tool.Recommended);
        }

        try
        {
            CommandResult result = await BoundedCommandRunner.RunAsync(
                executable, tool.Arguments, TimeSpan.FromSeconds(5), 256 * 1024,
                cancellationToken).ConfigureAwait(false);
            string firstLine = FirstMeaningfulLine(result.Output);
            if (!result.Successful)
            {
                return new(tool.Id, tool.Name, CheckStatus.Error, "命令异常", executable,
                    string.IsNullOrEmpty(firstLine) ? $"退出码 {result.ExitCode}" : firstLine,
                    tool.Recommended);
            }

            string? issue = ConfigurationIssue(tool, executable);
            return new(tool.Id, tool.Name,
                issue is null ? CheckStatus.Available : CheckStatus.Warning,
                string.IsNullOrEmpty(firstLine) ? "可用" : firstLine,
                executable,
                issue ?? (result.Truncated ? "输出过长，已截断" : "命令执行正常"),
                tool.Recommended);
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception exception) when (exception is IOException or TimeoutException)
        {
            return new(tool.Id, tool.Name, CheckStatus.Error, "无法执行", executable,
                exception.Message, tool.Recommended);
        }
    }

    private static CheckResult RuntimeResult() => new(
        "runtime",
        "FxTools .NET 运行时",
        CheckStatus.Available,
        RuntimeInformation.FrameworkDescription,
        AppContext.BaseDirectory,
        $"{RuntimeInformation.OSDescription} · {RuntimeInformation.ProcessArchitecture}",
        false);

    private static string? ConfigurationIssue(ToolDefinition tool, string executable)
    {
        if (tool.Id != "jdk")
        {
            return null;
        }
        string? javaHome = Environment.GetEnvironmentVariable("JAVA_HOME")?.Trim().Trim('"');
        if (string.IsNullOrWhiteSpace(javaHome))
        {
            return null;
        }
        try
        {
            return Path.GetFullPath(executable).StartsWith(
                Path.GetFullPath(javaHome), StringComparison.OrdinalIgnoreCase)
                ? null
                : $"PATH 中的 javac 与 JAVA_HOME 不一致: {javaHome}";
        }
        catch (Exception exception) when (exception is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return $"JAVA_HOME 不是有效路径: {javaHome}";
        }
    }

    private static string[] GetExecutableExtensions()
    {
        if (!OperatingSystem.IsWindows())
        {
            return [string.Empty];
        }
        string? pathExt = Environment.GetEnvironmentVariable("PATHEXT");
        IEnumerable<string> configured = (pathExt ?? ".COM;.EXE;.BAT;.CMD")
            .Split(';', StringSplitOptions.RemoveEmptyEntries)
            .Select(extension => extension.Trim().ToLowerInvariant())
            .Select(extension => extension.StartsWith('.') ? extension : $".{extension}");
        return configured.Concat([".com", ".exe", ".bat", ".cmd", string.Empty])
            .Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
    }

    public static string FirstMeaningfulLine(string? output) => output?
        .Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
        .FirstOrDefault() ?? string.Empty;

    private sealed record ToolDefinition(
        string Id,
        string Name,
        string Command,
        IReadOnlyList<string> Arguments,
        bool Recommended);
}

public sealed record EnvironmentReport(
    IReadOnlyList<CheckResult> Results,
    int AvailableCount,
    int RequiredIssueCount);

public sealed record CheckResult(
    string Id,
    string Name,
    CheckStatus Status,
    string Version,
    string Path,
    string Detail,
    bool Recommended);

public enum CheckStatus
{
    Available,
    Warning,
    Missing,
    Error
}
