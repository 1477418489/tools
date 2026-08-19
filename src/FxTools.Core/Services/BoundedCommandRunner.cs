using System.Diagnostics;
using System.Text;

namespace FxTools.Core.Services;

public static class BoundedCommandRunner
{
    public static async Task<CommandResult> RunAsync(
        string executable,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        int maxOutputCharacters,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(executable);
        ArgumentOutOfRangeException.ThrowIfLessThan(maxOutputCharacters, 1);

        ProcessStartInfo startInfo = CreateStartInfo(executable, arguments);
        using Process process = new() { StartInfo = startInfo };
        try
        {
            if (!process.Start())
            {
                throw new IOException($"无法启动命令: {executable}");
            }
        }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            throw new IOException($"无法启动命令: {executable}", exception);
        }

        Task<string> standardOutput = ReadBoundedAsync(process.StandardOutput, maxOutputCharacters);
        Task<string> standardError = ReadBoundedAsync(process.StandardError, maxOutputCharacters);
        using CancellationTokenSource timeoutSource = new(timeout);
        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken, timeoutSource.Token);
        try
        {
            await process.WaitForExitAsync(linked.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (timeoutSource.IsCancellationRequested
                                                 && !cancellationToken.IsCancellationRequested)
        {
            TryKill(process);
            await AwaitReadersAsync(standardOutput, standardError).ConfigureAwait(false);
            throw new TimeoutException($"命令执行超过 {timeout.TotalSeconds:0.#} 秒。");
        }
        catch (OperationCanceledException)
        {
            TryKill(process);
            await AwaitReadersAsync(standardOutput, standardError).ConfigureAwait(false);
            throw;
        }

        string output = await standardOutput.ConfigureAwait(false);
        string error = await standardError.ConfigureAwait(false);
        string combined = string.IsNullOrWhiteSpace(error)
            ? output
            : string.IsNullOrWhiteSpace(output) ? error : $"{output.TrimEnd()}\n{error}";
        bool truncated = combined.Length > maxOutputCharacters;
        if (truncated)
        {
            combined = combined[..maxOutputCharacters];
        }
        return new(process.ExitCode, combined.Trim(), truncated);
    }

    private static ProcessStartInfo CreateStartInfo(string executable, IReadOnlyList<string> arguments)
    {
        bool commandScript = OperatingSystem.IsWindows()
            && Path.GetExtension(executable) is ".cmd" or ".bat";
        ProcessStartInfo startInfo = new()
        {
            FileName = commandScript
                ? Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe"
                : executable,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };
        if (commandScript)
        {
            startInfo.ArgumentList.Add("/d");
            startInfo.ArgumentList.Add("/s");
            startInfo.ArgumentList.Add("/c");
            startInfo.ArgumentList.Add(BuildScriptCommand(executable, arguments));
        }
        else
        {
            foreach (string argument in arguments)
            {
                startInfo.ArgumentList.Add(argument);
            }
        }
        return startInfo;
    }

    private static string BuildScriptCommand(string executable, IReadOnlyList<string> arguments)
    {
        static string Quote(string value) => $"\"{value.Replace("\"", "\"\"")}\"";
        return string.Join(' ', new[] { Quote(executable) }.Concat(arguments.Select(Quote)));
    }

    private static async Task<string> ReadBoundedAsync(StreamReader reader, int limit)
    {
        char[] buffer = new char[4096];
        StringBuilder output = new(Math.Min(limit, 64 * 1024));
        while (true)
        {
            int read = await reader.ReadAsync(buffer).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }
            int remaining = limit + 1 - output.Length;
            if (remaining > 0)
            {
                output.Append(buffer, 0, Math.Min(read, remaining));
            }
        }
        return output.ToString();
    }

    private static async Task AwaitReadersAsync(params Task<string>[] readers)
    {
        try { await Task.WhenAll(readers).ConfigureAwait(false); }
        catch (IOException) { }
    }

    private static void TryKill(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
        }
    }
}

public readonly record struct CommandResult(int ExitCode, string Output, bool Truncated)
{
    public bool Successful => ExitCode == 0;
}
