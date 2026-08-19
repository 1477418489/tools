using System.Globalization;
using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace FxTools.App.Pages;

public sealed partial class RemindersPage : Page, IDisposable
{
    private readonly MemoReminderService service;
    private bool initialized;
    private bool disposed;

    internal RemindersPage(MemoReminderService service)
    {
        this.service = service;
        InitializeComponent();
        UnitBox.ItemsSource = new[] { "分钟", "小时", "天" };
        DateTimeOffset initial = DateTimeOffset.Now.AddMinutes(5);
        DateBox.Date = initial.Date;
        TimeBox.Time = initial.TimeOfDay;
        service.Changed += Service_Changed;
        service.Error += Service_Error;
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (initialized)
        {
            return;
        }
        initialized = true;
        await RefreshAsync();
    }

    private void ScheduleMode_SelectionChanged(object sender, RoutedEventArgs e)
    {
        if (IntervalOptions is null || AtTimeOptions is null)
        {
            return;
        }
        bool atTime = AtTimeModeButton.IsChecked == true;
        IntervalOptions.Visibility = atTime ? Visibility.Collapsed : Visibility.Visible;
        AtTimeOptions.Visibility = atTime ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void Add_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (AtTimeModeButton.IsChecked == true)
            {
                DateTime date = DateBox.Date.Date;
                DateTime localDateTime = DateTime.SpecifyKind(date.Add(TimeBox.Time), DateTimeKind.Unspecified);
                DateTimeOffset trigger = new(localDateTime, TimeZoneInfo.Local.GetUtcOffset(localDateTime));
                _ = await service.AddAtTimeAsync(ContentBox.Text, trigger);
            }
            else
            {
                _ = await service.AddIntervalAsync(
                    ContentBox.Text,
                    ReadNumber(IntervalBox, "提醒间隔"),
                    (IntervalUnit)UnitBox.SelectedIndex,
                    ReadNumber(TimesBox, "提醒次数"));
            }

            ContentBox.Text = string.Empty;
            ContentBox.Focus(FocusState.Programmatic);
            SetStatus("提醒已新增", true);
        }
        catch (Exception exception) when (exception is ArgumentException
                                          or InvalidOperationException
                                          or IOException
                                          or UnauthorizedAccessException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private async void Pause_Click(object sender, RoutedEventArgs e)
    {
        if (ReminderList.SelectedItem is not ReminderRow row)
        {
            return;
        }
        await RunActionAsync(() => service.PauseAsync(row.Id), "提醒已暂停");
    }

    private async void Resume_Click(object sender, RoutedEventArgs e)
    {
        if (ReminderList.SelectedItem is not ReminderRow row)
        {
            return;
        }
        await RunActionAsync(() => service.ResumeAsync(row.Id), "提醒已恢复");
    }

    private async void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (ReminderList.SelectedItem is not ReminderRow row)
        {
            return;
        }

        ContentDialog dialog = new()
        {
            XamlRoot = XamlRoot,
            Title = "删除提醒",
            Content = $"确定删除“{row.Content}”？",
            PrimaryButtonText = "删除",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Close
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }
        await RunActionAsync(() => service.RemoveAsync(row.Id), "提醒已删除");
    }

    private async Task RunActionAsync(Func<Task> action, string successMessage)
    {
        try
        {
            await action();
            SetStatus(successMessage, true);
        }
        catch (Exception exception) when (exception is InvalidOperationException
                                          or IOException
                                          or UnauthorizedAccessException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private void ReminderList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        UpdateButtons();
    }

    private void Service_Changed(object? sender, EventArgs e)
    {
        _ = DispatcherQueue.TryEnqueue(RefreshFromEventAsync);
    }

    private async void RefreshFromEventAsync() => await RefreshAsync();

    private void Service_Error(object? sender, string message)
    {
        _ = DispatcherQueue.TryEnqueue(() => SetStatus(message, false));
    }

    private async Task RefreshAsync()
    {
        try
        {
            long? selectedId = (ReminderList.SelectedItem as ReminderRow)?.Id;
            IReadOnlyList<MemoReminder> reminders = await service.GetSnapshotAsync();
            ReminderRow[] rows = reminders.OrderBy(static reminder => reminder.NextTriggerEpochMillis)
                .Select(ReminderRow.From).ToArray();
            ReminderList.ItemsSource = rows;
            if (selectedId is not null)
            {
                ReminderList.SelectedItem = rows.FirstOrDefault(row => row.Id == selectedId);
            }
            long activeCount = reminders.LongCount(static reminder => reminder.Active);
            CountText.Text = $"{activeCount:N0} 进行中 · {reminders.Count:N0} 条";
            SetStatus("提醒已加载", true);
            UpdateButtons();
        }
        catch (Exception exception) when (exception is InvalidOperationException or ObjectDisposedException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private void UpdateButtons()
    {
        ReminderRow? row = ReminderList.SelectedItem as ReminderRow;
        PauseButton.IsEnabled = row?.Active == true;
        ResumeButton.IsEnabled = row is { Active: false, RemainingCount: not 0 };
        DeleteButton.IsEnabled = row is not null;
    }

    private static int ReadNumber(NumberBox box, string fieldName)
    {
        if (double.IsNaN(box.Value)
            || box.Value < int.MinValue
            || box.Value > int.MaxValue
            || box.Value != Math.Truncate(box.Value))
        {
            throw new ArgumentException($"{fieldName}请输入有效整数。");
        }
        return checked((int)box.Value);
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush;
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }
        disposed = true;
        service.Changed -= Service_Changed;
        service.Error -= Service_Error;
    }

    private sealed record ReminderRow(
        long Id,
        string Content,
        string Schedule,
        string Remaining,
        string NextTime,
        string State,
        bool Active,
        int RemainingCount)
    {
        public static ReminderRow From(MemoReminder reminder)
        {
            string schedule = reminder.ScheduleMode == ReminderScheduleMode.AtTime
                ? "指定时间"
                : $"{reminder.Interval} {UnitName(reminder.Unit)}";
            string remaining = reminder.RemainingTimes < 0
                ? "不限"
                : reminder.RemainingTimes.ToString("N0", CultureInfo.CurrentCulture);
            string next = DateTimeOffset.FromUnixTimeMilliseconds(reminder.NextTriggerEpochMillis)
                .LocalDateTime.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.CurrentCulture);
            return new(reminder.Id, reminder.Content, schedule, remaining, next,
                reminder.Active ? "进行中" : reminder.RemainingTimes == 0 ? "已完成" : "已暂停",
                reminder.Active, reminder.RemainingTimes);
        }

        private static string UnitName(IntervalUnit? unit) => unit switch
        {
            IntervalUnit.MINUTES => "分钟",
            IntervalUnit.HOURS => "小时",
            IntervalUnit.DAYS => "天",
            _ => string.Empty
        };
    }
}
