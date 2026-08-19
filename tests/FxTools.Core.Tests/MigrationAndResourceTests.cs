using System.IO.Compression;
using FxTools.Core.Services;
using FxTools.Core.Windows;

namespace FxTools.Core.Tests;

public sealed class MigrationAndResourceTests : IDisposable
{
    private readonly string temporaryDirectory = Path.Combine(
        Path.GetTempPath(), $"FxTools.Migration.Tests.{Guid.NewGuid():N}");

    [Fact]
    public void LogMatcherReturnsEverySelectedStatusRule()
    {
        LogMonitorMatcher matcher = new(LogMonitorConfig.Default().Rules);

        IReadOnlyList<LogMonitorRule> matches = matcher.Match("upstream returned 429, then 503");

        Assert.Equal(["429", "503"], matches.Select(static rule => rule.Id));
        Assert.Empty(matcher.Match("codes 1429 and 5030 are not whole tokens"));
    }

    [Fact]
    public async Task LogStoreMigratesLegacySingleTriggerRule()
    {
        Directory.CreateDirectory(temporaryDirectory);
        string path = Path.Combine(temporaryDirectory, "log-monitor.json");
        await File.WriteAllTextAsync(path,
            """
            {
              "logFile": "C:\\logs\\service.log",
              "rules": [
                { "id": "429", "name": "HTTP 429", "expression": "429", "mode": "WHOLE_TOKEN", "caseSensitive": true, "enabled": true },
                { "id": "503", "name": "HTTP 503", "expression": "503", "mode": "WHOLE_TOKEN", "caseSensitive": true, "enabled": true }
              ],
              "automation": {
                "enabled": true,
                "triggerRuleId": "503",
                "targetWindow": "pid:123 | Command Prompt",
                "typeText": false,
                "text": "",
                "pressEnter": true,
                "startAtMatch": 1,
                "everyMatches": 1,
                "maxExecutions": 0
              }
            }
            """);
        using LogMonitorStore store = new(path);

        LogMonitorConfig loaded = await store.LoadAsync();

        Assert.Equal(["503"], loaded.Automation.TriggerRuleIds);
    }

    [Fact]
    public async Task LogTailerCapsLineLengthAndLinesPerPoll()
    {
        Directory.CreateDirectory(temporaryDirectory);
        string path = Path.Combine(temporaryDirectory, "large.log");
        string oversized = new('x', LogFileTailer.MaxLineBytes + 500);
        string content = oversized + "\n" + string.Join('\n', Enumerable.Range(0, 250)) + "\n";
        await File.WriteAllTextAsync(path, content);
        LogFileTailer tailer = new(path);

        IReadOnlyList<string> first = await tailer.PollAsync(startAtEnd: false);
        IReadOnlyList<string> second = await tailer.PollAsync(startAtEnd: false);

        Assert.Equal(LogFileTailer.MaxLinesPerPoll, first.Count);
        Assert.Contains("[单行日志已截断]", first[0], StringComparison.Ordinal);
        Assert.InRange(first[0].Length, 1, LogFileTailer.MaxLineBytes + 32);
        Assert.Equal(51, second.Count);
    }

    [Fact]
    public void WindowSelectorRequiresPidAndExactTitle()
    {
        WindowTarget target = WindowsWindowService.ParseSelector("pid:321 | Administrator: Command Prompt");

        Assert.Equal(321, target.ProcessId);
        Assert.Equal("Administrator: Command Prompt", target.WindowTitle);
        Assert.Throws<ArgumentException>(() => WindowsWindowService.ParseSelector("Command Prompt"));
        Assert.Throws<ArgumentException>(() => WindowsWindowService.ParseSelector("pid:0 | Command Prompt"));
    }

    [Fact]
    public void JarArgumentParserPreservesQuotedArguments()
    {
        string[] arguments = WindowsDetachedProcessLauncher.ParseArguments(
            "-Xmx256m -Dname=\"Fx Tools\" \"C:\\data folder\\config.json\"");

        Assert.Equal(["-Xmx256m", "-Dname=Fx Tools", "C:\\data folder\\config.json"], arguments);
    }

    [Fact]
    public async Task ReminderStoreReadsLegacyJavaEnumValues()
    {
        Directory.CreateDirectory(temporaryDirectory);
        string path = Path.Combine(temporaryDirectory, "memo_reminders.json");
        await File.WriteAllTextAsync(path,
            """
            [
              {
                "id": 10,
                "content": "检查服务",
                "scheduleMode": "INTERVAL",
                "interval": 5,
                "unit": "MINUTES",
                "totalTimes": 2,
                "remainingTimes": 2,
                "nextTriggerEpochMillis": 4102444800000,
                "active": true
              }
            ]
            """);
        using MemoReminderStore store = new(path);

        List<MemoReminder> reminders = await store.LoadAsync();

        MemoReminder reminder = Assert.Single(reminders);
        Assert.Equal(ReminderScheduleMode.Interval, reminder.ScheduleMode);
        Assert.Equal(IntervalUnit.MINUTES, reminder.Unit);
    }

    [Fact]
    public async Task ReminderSchedulerCompletesDueAtTimeReminder()
    {
        Directory.CreateDirectory(temporaryDirectory);
        string path = Path.Combine(temporaryDirectory, "due-reminders.json");
        long due = DateTimeOffset.Now.AddSeconds(-1).ToUnixTimeMilliseconds();
        await File.WriteAllTextAsync(path,
            $$"""
            [{
              "id": 20,
              "content": "到期事项",
              "scheduleMode": "AT_TIME",
              "interval": 0,
              "unit": null,
              "totalTimes": 1,
              "remainingTimes": 1,
              "nextTriggerEpochMillis": {{due}},
              "active": true
            }]
            """);
        await using MemoReminderService service = new(path);
        TaskCompletionSource completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        service.ReminderDueAsync = (reminder, _) =>
        {
            completion.TrySetResult();
            return Task.FromResult(ReminderAction.Complete);
        };

        await service.InitializeAsync();
        await completion.Task.WaitAsync(TimeSpan.FromSeconds(2));

        MemoReminder current = Assert.Single(await WaitForCompletedReminderAsync(service));
        Assert.False(current.Active);
        Assert.Equal(0, current.RemainingTimes);
    }

    [Fact]
    public async Task SettingsRollbackWhenStartupRegistrationFails()
    {
        Directory.CreateDirectory(temporaryDirectory);
        string path = Path.Combine(temporaryDirectory, "settings.json");
        FakeStartupRegistration startup = new();
        using AppSettingsService service = new(path, startup);
        _ = await service.InitializeAsync();
        await service.UpdateAsync(new AppSettings
        {
            CloseToTray = false,
            ReminderSoundEnabled = false,
            StartWithWindows = true
        });
        startup.FailChanges = true;

        await Assert.ThrowsAsync<IOException>(() => service.UpdateAsync(new AppSettings
        {
            CloseToTray = true,
            ReminderSoundEnabled = true,
            StartWithWindows = false
        }));

        Assert.True(service.Current.StartWithWindows);
        Assert.False(service.Current.CloseToTray);
    }

    [Fact]
    public async Task BackupStreamsFilesAndRejectsDestinationInsideDataDirectory()
    {
        string source = Path.Combine(temporaryDirectory, "data");
        string output = Path.Combine(temporaryDirectory, "backup.zip");
        Directory.CreateDirectory(Path.Combine(source, "nested"));
        await File.WriteAllTextAsync(Path.Combine(source, "settings.json"), "{}");
        await File.WriteAllTextAsync(Path.Combine(source, "nested", "items.json"), "[]");

        BackupResult result = await AppDataBackupService.ExportAsync(source, output);

        Assert.Equal(2, result.FileCount);
        using ZipArchive archive = ZipFile.OpenRead(output);
        Assert.Equal(["nested/items.json", "settings.json"],
            archive.Entries.Select(static entry => entry.FullName).Order(StringComparer.Ordinal));
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            AppDataBackupService.ExportAsync(source, Path.Combine(source, "bad.zip")));
    }

    private static async Task<IReadOnlyList<MemoReminder>> WaitForCompletedReminderAsync(
        MemoReminderService service)
    {
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(2));
        while (true)
        {
            IReadOnlyList<MemoReminder> reminders = await service.GetSnapshotAsync(timeout.Token);
            if (reminders.All(static reminder => !reminder.Active))
            {
                return reminders;
            }
            await Task.Delay(10, timeout.Token);
        }
    }

    public void Dispose()
    {
        if (Directory.Exists(temporaryDirectory))
        {
            Directory.Delete(temporaryDirectory, recursive: true);
        }
        GC.SuppressFinalize(this);
    }

    private sealed class FakeStartupRegistration : IStartupRegistration
    {
        public bool Enabled { get; private set; }
        public bool FailChanges { get; set; }

        public bool IsEnabled() => Enabled;

        public void SetEnabled(bool enabled)
        {
            if (FailChanges)
            {
                throw new IOException("模拟注册表写入失败");
            }
            Enabled = enabled;
        }
    }
}
