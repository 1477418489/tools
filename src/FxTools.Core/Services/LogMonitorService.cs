namespace FxTools.Core.Services;

public sealed class LogMonitorService : IAsyncDisposable
{
    private readonly LogMonitorAutomationService automation = new();
    private CancellationTokenSource? monitorCancellation;
    private Task? monitorTask;

    public LogMonitorService() => automation.EventRaised += (_, value) => AutomationEvent?.Invoke(this, value);

    public event EventHandler<LogMonitorMatch>? MatchFound;
    public event EventHandler<string>? StatusChanged;
    public event EventHandler<AutomationEvent>? AutomationEvent;

    public bool Running => monitorTask is { IsCompleted: false };

    public void Start(LogMonitorConfig config, bool startAtEnd = true)
    {
        if (Running) { throw new InvalidOperationException("日志监控已经运行。"); }
        LogMonitorStore.Validate(config);
        monitorCancellation = new();
        automation.Configure(config.Automation);
        monitorTask = RunAsync(config, startAtEnd, monitorCancellation.Token);
    }

    public async Task StopAsync()
    {
        CancellationTokenSource? cancellation = monitorCancellation;
        monitorCancellation = null;
        cancellation?.Cancel();
        Task? pending = monitorTask;
        monitorTask = null;
        if (pending is not null)
        {
            try { await pending.ConfigureAwait(false); } catch (OperationCanceledException) { }
        }
        cancellation?.Dispose();
        automation.Configure(null);
    }

    private async Task RunAsync(LogMonitorConfig config, bool startAtEnd, CancellationToken cancellationToken)
    {
        LogFileTailer tailer = new(config.LogFile);
        LogMonitorMatcher matcher = new(config.Rules);
        bool first = true;
        using PeriodicTimer timer = new(TimeSpan.FromMilliseconds(500));
        StatusChanged?.Invoke(this, "日志监控已启动");
        while (!cancellationToken.IsCancellationRequested)
        {
            IReadOnlyList<string> lines = await tailer.PollAsync(first && startAtEnd, cancellationToken).ConfigureAwait(false);
            first = false;
            List<LogMonitorMatch> matches = [];
            foreach (string line in lines)
            {
                foreach (LogMonitorRule rule in matcher.Match(line))
                {
                    LogMonitorMatch match = new(DateTimeOffset.Now, rule.Id, rule.Name, line);
                    matches.Add(match);
                    MatchFound?.Invoke(this, match);
                }
            }
            if (matches.Count > 0) { automation.Accept(matches); }
            await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        await automation.DisposeAsync().ConfigureAwait(false);
    }
}
