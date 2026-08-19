using FxTools.Core.Infrastructure;
using System.Text.Json.Serialization;

namespace FxTools.Core.Services;

public enum ReminderScheduleMode
{
    [JsonStringEnumMemberName("INTERVAL")]
    Interval,
    [JsonStringEnumMemberName("AT_TIME")]
    AtTime
}

public enum ReminderAction
{
    Complete,
    SnoozeFiveMinutes
}

public sealed class MemoReminder
{
    public long Id { get; set; }
    public string Content { get; set; } = string.Empty;
    public ReminderScheduleMode ScheduleMode { get; set; } = ReminderScheduleMode.Interval;
    public int Interval { get; set; }
    public IntervalUnit? Unit { get; set; }
    public int TotalTimes { get; set; }
    public int RemainingTimes { get; set; }
    public long NextTriggerEpochMillis { get; set; }
    public bool Active { get; set; } = true;

    public TimeSpan GetInterval()
    {
        if (ScheduleMode != ReminderScheduleMode.Interval || Interval <= 0 || Unit is null)
        {
            throw new InvalidOperationException("仅周期提醒支持计算提醒间隔。");
        }

        return Unit.Value switch
        {
            IntervalUnit.MINUTES => TimeSpan.FromMinutes(Interval),
            IntervalUnit.HOURS => TimeSpan.FromHours(Interval),
            IntervalUnit.DAYS => TimeSpan.FromDays(Interval),
            _ => throw new InvalidOperationException("提醒时间单位无效。")
        };
    }

    public MemoReminder Copy() => new()
    {
        Id = Id,
        Content = Content,
        ScheduleMode = ScheduleMode,
        Interval = Interval,
        Unit = Unit,
        TotalTimes = TotalTimes,
        RemainingTimes = RemainingTimes,
        NextTriggerEpochMillis = NextTriggerEpochMillis,
        Active = Active
    };
}

public sealed class MemoReminderStore : IDisposable
{
    internal const int MaximumReminderCount = 1000;
    internal const int MaximumContentLength = 10_000;

    private readonly AtomicJsonStore store;
    private readonly string path;

    public MemoReminderStore(string? path = null)
    {
        this.path = path ?? AppDataPaths.DataFile("memo_reminders.json");
        store = new AtomicJsonStore(this.path);
    }

    public async Task<List<MemoReminder>> LoadAsync(CancellationToken cancellationToken = default)
    {
        List<MemoReminder> reminders = await store.LoadAsync(
            static () => new List<MemoReminder>(), cancellationToken).ConfigureAwait(false);
        Validate(reminders);
        return reminders;
    }

    public async Task SaveAsync(
        IReadOnlyList<MemoReminder> reminders,
        CancellationToken cancellationToken = default)
    {
        Validate(reminders);
        if (File.Exists(path))
        {
            _ = await LoadAsync(cancellationToken).ConfigureAwait(false);
        }

        await store.SaveAsync(reminders, cancellationToken).ConfigureAwait(false);
    }

    public static void Validate(IReadOnlyList<MemoReminder> reminders)
    {
        ArgumentNullException.ThrowIfNull(reminders);
        if (reminders.Count > MaximumReminderCount)
        {
            throw new InvalidDataException($"提醒数量不能超过 {MaximumReminderCount:N0} 条。");
        }

        HashSet<long> ids = [];
        foreach (MemoReminder? reminder in reminders)
        {
            if (reminder is null
                || reminder.Id <= 0
                || !ids.Add(reminder.Id)
                || string.IsNullOrWhiteSpace(reminder.Content)
                || reminder.Content.Length > MaximumContentLength
                || reminder.NextTriggerEpochMillis <= 0)
            {
                throw new InvalidDataException("备忘提醒配置不符合当前格式。");
            }

            if (reminder.ScheduleMode == ReminderScheduleMode.AtTime)
            {
                bool invalid = reminder.Interval != 0
                    || reminder.Unit is not null
                    || reminder.TotalTimes != 1
                    || reminder.RemainingTimes is < 0 or > 1
                    || (reminder.Active && reminder.RemainingTimes == 0);
                if (invalid)
                {
                    throw new InvalidDataException("指定时间提醒配置不符合当前格式。");
                }
            }
            else
            {
                bool invalidUnlimited = reminder.TotalTimes <= 0 && reminder.RemainingTimes != -1;
                bool invalidFinite = reminder.TotalTimes > 0
                    && (reminder.RemainingTimes < 0 || reminder.RemainingTimes > reminder.TotalTimes);
                if (reminder.Interval <= 0
                    || reminder.Unit is null
                    || invalidUnlimited
                    || invalidFinite
                    || (reminder.Active && reminder.RemainingTimes == 0))
                {
                    throw new InvalidDataException("周期提醒配置不符合当前格式。");
                }

                _ = reminder.GetInterval();
            }
        }
    }

    public void Dispose() => store.Dispose();
}

public sealed class MemoReminderService : IAsyncDisposable
{
    private static readonly TimeSpan SnoozeDuration = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan MaximumWait = TimeSpan.FromDays(1);

    private readonly MemoReminderStore store;
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly SemaphoreSlim wakeSignal = new(0, 1);
    private readonly CancellationTokenSource shutdown = new();
    private List<MemoReminder> reminders = [];
    private Task? schedulerTask;
    private bool initialized;
    private bool disposed;

    public MemoReminderService(string? path = null)
    {
        store = new MemoReminderStore(path);
    }

    public Func<MemoReminder, CancellationToken, Task<ReminderAction>>? ReminderDueAsync { get; set; }

    public event EventHandler? Changed;
    public event EventHandler<string>? Error;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        ThrowIfDisposed();
        List<MemoReminder> loaded = await store.LoadAsync(cancellationToken).ConfigureAwait(false);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (initialized)
            {
                return;
            }

            reminders = loaded.Select(static reminder => reminder.Copy()).ToList();
            initialized = true;
            schedulerTask = RunSchedulerAsync(shutdown.Token);
        }
        finally
        {
            gate.Release();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public async Task<IReadOnlyList<MemoReminder>> GetSnapshotAsync(
        CancellationToken cancellationToken = default)
    {
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            EnsureInitialized();
            return reminders.Select(static reminder => reminder.Copy()).ToArray();
        }
        finally
        {
            gate.Release();
        }
    }

    public Task<MemoReminder> AddIntervalAsync(
        string content,
        int interval,
        IntervalUnit unit,
        int totalTimes,
        CancellationToken cancellationToken = default)
    {
        if (interval <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(interval), "提醒间隔必须大于 0。");
        }

        DateTimeOffset now = DateTimeOffset.Now;
        TimeSpan duration = unit switch
        {
            IntervalUnit.MINUTES => TimeSpan.FromMinutes(interval),
            IntervalUnit.HOURS => TimeSpan.FromHours(interval),
            IntervalUnit.DAYS => TimeSpan.FromDays(interval),
            _ => throw new ArgumentOutOfRangeException(nameof(unit), "提醒时间单位无效。")
        };
        long nextTrigger = now.Add(duration).ToUnixTimeMilliseconds();
        return AddAsync(new MemoReminder
        {
            Content = NormalizeContent(content),
            ScheduleMode = ReminderScheduleMode.Interval,
            Interval = interval,
            Unit = unit,
            TotalTimes = totalTimes,
            RemainingTimes = totalTimes <= 0 ? -1 : totalTimes,
            NextTriggerEpochMillis = nextTrigger,
            Active = true
        }, cancellationToken);
    }

    public Task<MemoReminder> AddAtTimeAsync(
        string content,
        DateTimeOffset triggerTime,
        CancellationToken cancellationToken = default)
    {
        if (triggerTime <= DateTimeOffset.Now)
        {
            throw new ArgumentOutOfRangeException(nameof(triggerTime), "指定提醒时间必须晚于当前时间。");
        }

        return AddAsync(new MemoReminder
        {
            Content = NormalizeContent(content),
            ScheduleMode = ReminderScheduleMode.AtTime,
            TotalTimes = 1,
            RemainingTimes = 1,
            NextTriggerEpochMillis = triggerTime.ToUnixTimeMilliseconds(),
            Active = true
        }, cancellationToken);
    }

    public Task PauseAsync(long id, CancellationToken cancellationToken = default) =>
        MutateAsync(id, static reminder => reminder.Active = false, cancellationToken);

    public Task ResumeAsync(long id, CancellationToken cancellationToken = default) =>
        MutateAsync(id, static reminder =>
        {
            if (reminder.RemainingTimes == 0)
            {
                throw new InvalidOperationException("该提醒次数已耗尽，请新建提醒。");
            }

            reminder.Active = true;
            if (reminder.ScheduleMode == ReminderScheduleMode.Interval
                && reminder.NextTriggerEpochMillis <= DateTimeOffset.Now.ToUnixTimeMilliseconds())
            {
                reminder.NextTriggerEpochMillis = DateTimeOffset.Now
                    .Add(reminder.GetInterval()).ToUnixTimeMilliseconds();
            }
        }, cancellationToken);

    public async Task RemoveAsync(long id, CancellationToken cancellationToken = default)
    {
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            EnsureInitialized();
            int index = reminders.FindIndex(reminder => reminder.Id == id);
            if (index < 0)
            {
                return;
            }

            List<MemoReminder> previous = CopyReminders();
            reminders.RemoveAt(index);
            try
            {
                await store.SaveAsync(reminders, cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                reminders = previous;
                throw;
            }
        }
        finally
        {
            gate.Release();
        }

        NotifyChanged();
    }

    private async Task<MemoReminder> AddAsync(
        MemoReminder reminder,
        CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        MemoReminder result;
        try
        {
            EnsureInitialized();
            if (reminders.Count >= MemoReminderStore.MaximumReminderCount)
            {
                throw new InvalidOperationException(
                    $"提醒数量不能超过 {MemoReminderStore.MaximumReminderCount:N0} 条。");
            }

            long timestampId = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            long maximumId = reminders.Count == 0 ? 0 : reminders.Max(static value => value.Id);
            reminder.Id = Math.Max(timestampId, checked(maximumId + 1));
            List<MemoReminder> previous = CopyReminders();
            reminders.Add(reminder);
            try
            {
                await store.SaveAsync(reminders, cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                reminders = previous;
                throw;
            }

            result = reminder.Copy();
        }
        finally
        {
            gate.Release();
        }

        NotifyChanged();
        return result;
    }

    private async Task MutateAsync(
        long id,
        Action<MemoReminder> mutation,
        CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            EnsureInitialized();
            MemoReminder reminder = reminders.FirstOrDefault(value => value.Id == id)
                ?? throw new InvalidOperationException("提醒不存在或已被删除。");
            List<MemoReminder> previous = CopyReminders();
            try
            {
                mutation(reminder);
                MemoReminderStore.Validate(reminders);
                await store.SaveAsync(reminders, cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                reminders = previous;
                throw;
            }
        }
        finally
        {
            gate.Release();
        }

        NotifyChanged();
    }

    private async Task RunSchedulerAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                MemoReminder? next = await FindNextAsync(cancellationToken).ConfigureAwait(false);
                if (next is null)
                {
                    await wakeSignal.WaitAsync(cancellationToken).ConfigureAwait(false);
                    continue;
                }

                TimeSpan delay = DateTimeOffset.FromUnixTimeMilliseconds(next.NextTriggerEpochMillis)
                    - DateTimeOffset.Now;
                if (delay > TimeSpan.Zero)
                {
                    using CancellationTokenSource waitCancellation =
                        CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                    Task delayTask = Task.Delay(delay > MaximumWait ? MaximumWait : delay,
                        waitCancellation.Token);
                    Task signalTask = wakeSignal.WaitAsync(waitCancellation.Token);
                    Task completed = await Task.WhenAny(delayTask, signalTask).ConfigureAwait(false);
                    await waitCancellation.CancelAsync().ConfigureAwait(false);
                    try
                    {
                        await completed.ConfigureAwait(false);
                    }
                    catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                    {
                        throw;
                    }
                    continue;
                }

                ReminderAction action = ReminderAction.SnoozeFiveMinutes;
                try
                {
                    Func<MemoReminder, CancellationToken, Task<ReminderAction>>? handler = ReminderDueAsync;
                    if (handler is null)
                    {
                        await Task.Delay(SnoozeDuration, cancellationToken).ConfigureAwait(false);
                        continue;
                    }

                    action = await handler(next.Copy(), cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception exception)
                {
                    Error?.Invoke(this, $"显示提醒失败: {exception.Message}");
                }

                await ApplyDueActionAsync(next, action, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
    }

    private async Task<MemoReminder?> FindNextAsync(CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            return reminders
                .Where(static reminder => reminder.Active && reminder.RemainingTimes != 0)
                .MinBy(static reminder => reminder.NextTriggerEpochMillis)
                ?.Copy();
        }
        finally
        {
            gate.Release();
        }
    }

    private async Task ApplyDueActionAsync(
        MemoReminder due,
        ReminderAction action,
        CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        bool changed = false;
        try
        {
            MemoReminder? current = reminders.FirstOrDefault(reminder => reminder.Id == due.Id);
            if (current is null
                || !current.Active
                || current.RemainingTimes == 0
                || current.NextTriggerEpochMillis != due.NextTriggerEpochMillis)
            {
                return;
            }

            List<MemoReminder> previous = CopyReminders();
            DateTimeOffset now = DateTimeOffset.Now;
            if (action == ReminderAction.Complete)
            {
                AdvanceAfterCompletion(current, now);
            }
            else
            {
                current.NextTriggerEpochMillis = now.Add(SnoozeDuration).ToUnixTimeMilliseconds();
            }

            try
            {
                await store.SaveAsync(reminders, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
                reminders = previous;
                current = reminders.First(reminder => reminder.Id == due.Id);
                current.NextTriggerEpochMillis = now.Add(SnoozeDuration).ToUnixTimeMilliseconds();
                Error?.Invoke(this, $"保存提醒状态失败，已在本次运行中延后 5 分钟: {exception.Message}");
            }

            changed = true;
        }
        finally
        {
            gate.Release();
        }

        if (changed)
        {
            NotifyChanged();
        }
    }

    internal static void AdvanceAfterCompletion(MemoReminder reminder, DateTimeOffset now)
    {
        if (reminder.RemainingTimes > 0)
        {
            reminder.RemainingTimes--;
        }

        if (reminder.ScheduleMode == ReminderScheduleMode.AtTime
            || reminder.RemainingTimes == 0)
        {
            reminder.RemainingTimes = 0;
            reminder.Active = false;
            return;
        }

        reminder.NextTriggerEpochMillis = now.Add(reminder.GetInterval()).ToUnixTimeMilliseconds();
    }

    private static string NormalizeContent(string content)
    {
        string value = content?.Trim() ?? string.Empty;
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException("备忘内容不能为空。", nameof(content));
        }
        if (value.Length > MemoReminderStore.MaximumContentLength)
        {
            throw new ArgumentException(
                $"备忘内容不能超过 {MemoReminderStore.MaximumContentLength:N0} 个字符。", nameof(content));
        }
        return value;
    }

    private List<MemoReminder> CopyReminders() =>
        reminders.Select(static reminder => reminder.Copy()).ToList();

    private void NotifyChanged()
    {
        SignalScheduler();
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private void SignalScheduler()
    {
        if (wakeSignal.CurrentCount == 0)
        {
            try
            {
                wakeSignal.Release();
            }
            catch (SemaphoreFullException)
            {
            }
        }
    }

    private void EnsureInitialized()
    {
        if (!initialized)
        {
            throw new InvalidOperationException("提醒服务尚未初始化。");
        }
    }

    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);

    public async ValueTask DisposeAsync()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;
        await shutdown.CancelAsync().ConfigureAwait(false);
        SignalScheduler();
        if (schedulerTask is not null)
        {
            try
            {
                await schedulerTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        shutdown.Dispose();
        gate.Dispose();
        wakeSignal.Dispose();
        store.Dispose();
    }
}
