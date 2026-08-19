using System.Threading.Channels;
using FxTools.Core.Infrastructure;
using FxTools.Core.Windows;

namespace FxTools.Core.Services;

public sealed class LogMonitorAutomationService : IAsyncDisposable
{
    private readonly Channel<ScheduledAction> queue = Channel.CreateBounded<ScheduledAction>(new BoundedChannelOptions(
        MemoryLimits.AutomationQueueEntries)
    {
        FullMode = BoundedChannelFullMode.Wait,
        SingleReader = true,
        SingleWriter = false
    });
    private readonly CancellationTokenSource shutdown = new();
    private readonly Task worker;
    private readonly object sync = new();
    private LogMonitorAutomation? automation;
    private long generation;
    private long matchCount;
    private long executions;
    private long reserved;

    public LogMonitorAutomationService() => worker = WorkerAsync();

    public event EventHandler<AutomationEvent>? EventRaised;

    public void Configure(LogMonitorAutomation? configuration)
    {
        lock (sync)
        {
            automation = configuration;
            generation++;
            matchCount = 0;
            executions = 0;
            reserved = 0;
        }
    }

    public void Accept(IReadOnlyList<LogMonitorMatch> matches)
    {
        foreach (LogMonitorMatch match in matches)
        {
            ScheduledAction? action = null;
            lock (sync)
            {
                LogMonitorAutomation? current = automation;
                if (current?.Enabled != true || !current.TriggerRuleIds.Contains(match.RuleId, StringComparer.Ordinal)) { continue; }
                matchCount = matchCount == long.MaxValue ? matchCount : matchCount + 1;
                bool eligible = matchCount >= current.StartAtMatch
                                && (matchCount - current.StartAtMatch) % current.EveryMatches == 0;
                bool limited = current.MaxExecutions > 0 && executions + reserved >= current.MaxExecutions;
                if (eligible && !limited)
                {
                    reserved++;
                    action = new(generation, matchCount, Clone(current));
                }
            }
            if (action is not null && !queue.Writer.TryWrite(action))
            {
                lock (sync) { if (reserved > 0) { reserved--; } }
                Publish(new(AutomationEventType.Error, action.MatchCount, "自动响应队列已满，动作已跳过。"));
            }
        }
    }

    private async Task WorkerAsync()
    {
        try
        {
            await foreach (ScheduledAction action in queue.Reader.ReadAllAsync(shutdown.Token).ConfigureAwait(false))
            {
                bool success = false;
                try
                {
                    if (!IsCurrent(action.Generation)) { continue; }
                    if (action.Configuration.RemoteCheckEnabled
                        && !await RemoteAllowsAsync(action.Configuration, shutdown.Token).ConfigureAwait(false))
                    {
                        PublishIfCurrent(action, AutomationEventType.Skipped, "远程判断未允许输入。");
                        continue;
                    }
                    WindowTarget target = WindowsWindowService.ParseSelector(action.Configuration.TargetWindow);
                    InputSendResult result = await WindowsWindowService.SendInputAsync(
                        target,
                        action.Configuration.TypeText ? action.Configuration.Text : string.Empty,
                        action.Configuration.PressEnter,
                        shutdown.Token).ConfigureAwait(false);
                    success = result.Success;
                    PublishIfCurrent(action, success ? AutomationEventType.Executed : AutomationEventType.Error, result.Message);
                }
                catch (Exception exception) when (exception is ArgumentException or InvalidOperationException
                                                  or HttpRequestException or IOException or PlatformNotSupportedException)
                {
                    PublishIfCurrent(action, AutomationEventType.Error, $"自动响应失败: {exception.Message}");
                }
                finally
                {
                    lock (sync)
                    {
                        if (action.Generation == generation)
                        {
                            if (reserved > 0) { reserved--; }
                            if (success && executions < long.MaxValue) { executions++; }
                        }
                    }
                }
            }
        }
        catch (OperationCanceledException) when (shutdown.IsCancellationRequested) { }
    }

    private static async Task<bool> RemoteAllowsAsync(LogMonitorAutomation config, CancellationToken cancellationToken)
    {
        HttpRequestResult result = await HttpRequestService.SendAsync(
            new(config.RemoteUrl, "GET", string.Empty, string.Empty, TimeSpan.FromSeconds(5)),
            cancellationToken).ConfigureAwait(false);
        if (result.StatusCode is < 200 or >= 300) { return false; }
        bool contains = result.Body.Contains(config.RemoteKeyword, StringComparison.Ordinal);
        return contains
            ? config.RemoteMatchAction == LogRemoteMatchAction.ContinueInput
            : config.RemoteMatchAction == LogRemoteMatchAction.NoAction;
    }

    private bool IsCurrent(long candidate)
    {
        lock (sync) { return candidate == generation && automation?.Enabled == true; }
    }

    private void PublishIfCurrent(ScheduledAction action, AutomationEventType type, string message)
    {
        if (IsCurrent(action.Generation)) { Publish(new(type, action.MatchCount, message)); }
    }

    private void Publish(AutomationEvent value) => EventRaised?.Invoke(this, value);

    private static LogMonitorAutomation Clone(LogMonitorAutomation value) => new()
    {
        Enabled = value.Enabled,
        TriggerRuleIds = [.. value.TriggerRuleIds],
        TargetWindow = value.TargetWindow,
        TypeText = value.TypeText,
        Text = value.Text,
        PressEnter = value.PressEnter,
        StartAtMatch = value.StartAtMatch,
        EveryMatches = value.EveryMatches,
        MaxExecutions = value.MaxExecutions,
        RemoteCheckEnabled = value.RemoteCheckEnabled,
        RemoteUrl = value.RemoteUrl,
        RemoteKeyword = value.RemoteKeyword,
        RemoteMatchAction = value.RemoteMatchAction
    };

    public async ValueTask DisposeAsync()
    {
        shutdown.Cancel();
        queue.Writer.TryComplete();
        try { await worker.ConfigureAwait(false); } catch (OperationCanceledException) { }
        shutdown.Dispose();
    }

    private sealed record ScheduledAction(long Generation, long MatchCount, LogMonitorAutomation Configuration);
}

public sealed record AutomationEvent(AutomationEventType Type, long MatchCount, string Message);
public enum AutomationEventType { Executed, Skipped, Error }
