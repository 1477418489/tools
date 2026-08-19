using System.Collections.ObjectModel;
using System.Globalization;
using FxTools.Core.Infrastructure;
using FxTools.Core.Services;
using FxTools.Core.Windows;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace FxTools.App.Pages;

public sealed partial class LogMonitorPage : Page, IAsyncDisposable
{
    private readonly LogMonitorStore store = new();
    private readonly LogMonitorService service = new();
    private readonly ObservableCollection<MatchRow> matches = [];
    private readonly BoundedTextBuffer eventLogs = new(MemoryLimits.DisplayLogLines, MemoryLimits.DisplayLogCharacters, MemoryLimits.SingleLogCharacters);
    private LogMonitorConfig config = LogMonitorConfig.Default();
    private List<WindowTarget> windows = [];
    private bool initialized;

    public LogMonitorPage()
    {
        InitializeComponent(); MatchList.ItemsSource = matches;
        RuleModeBox.ItemsSource = new[] { "包含", "完整词元", "正则表达式" };
        RemoteActionBox.ItemsSource = new[] { "继续输入", "不执行输入" };
        service.MatchFound += Service_MatchFound; service.StatusChanged += Service_StatusChanged; service.AutomationEvent += Service_AutomationEvent;
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (initialized) { return; }
        initialized = true;
        try { config = await store.LoadAsync(); ApplyConfig(); SetStatus("配置已加载", true); }
        catch (Exception exception) when (exception is IOException or System.Text.Json.JsonException) { SetStatus(exception.Message, false); }
    }

    private async void ChooseLog_Click(object sender, RoutedEventArgs e)
    {
        FileOpenPicker picker = new(); picker.FileTypeFilter.Add("*"); InitializeWithWindow.Initialize(picker, App.MainWindowHandle);
        StorageFile? file = await picker.PickSingleFileAsync(); if (file is not null) { LogPathBox.Text = file.Path; }
    }

    private async void Save_Click(object sender, RoutedEventArgs e) => await SaveConfigAsync();

    private async Task<bool> SaveConfigAsync()
    {
        try { CaptureConfig(); await store.SaveAsync(config); SetStatus("配置已保存", true); return true; }
        catch (Exception exception) when (exception is ArgumentException or IOException or InvalidDataException) { SetStatus(exception.Message, false); return false; }
    }

    private async void Start_Click(object sender, RoutedEventArgs e)
    {
        if (!await SaveConfigAsync()) { return; }
        try { matches.Clear(); service.Start(config, startAtEnd: true); SetRunning(true); SetStatus("监控已启动，从文件末尾等待新日志", true); }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException or IOException) { SetStatus(exception.Message, false); }
    }

    private async void Stop_Click(object sender, RoutedEventArgs e) { await service.StopAsync(); SetRunning(false); SetStatus("监控已停止", true); }

    private async void RefreshWindows_Click(object sender, RoutedEventArgs e)
    {
        Progress.IsActive = true;
        try
        {
            windows = (await Task.Run(() => WindowsWindowService.ListVisibleWindows(Environment.ProcessId))).ToList();
            WindowBox.ItemsSource = windows; SetStatus($"读取到 {windows.Count:N0} 个可选窗口", true);
        }
        catch (Exception exception) when (exception is PlatformNotSupportedException or System.ComponentModel.Win32Exception) { SetStatus(exception.Message, false); }
        finally { Progress.IsActive = false; }
    }

    private void NewRule_Click(object sender, RoutedEventArgs e) { RuleList.SelectedItem = null; RuleNameBox.Text = RuleExpressionBox.Text = string.Empty; RuleModeBox.SelectedIndex = 1; RuleEnabledCheck.IsChecked = CaseSensitiveCheck.IsChecked = true; }

    private void ApplyRule_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(RuleNameBox.Text) || string.IsNullOrWhiteSpace(RuleExpressionBox.Text)) { SetStatus("规则名称和表达式不能为空", false); return; }
        int index = RuleList.SelectedIndex;
        LogMonitorRule rule = index >= 0 ? config.Rules[index] : new() { Id = Guid.NewGuid().ToString("N") };
        rule.Name = RuleNameBox.Text.Trim(); rule.Expression = RuleExpressionBox.Text; rule.Mode = (LogMatchMode)RuleModeBox.SelectedIndex;
        rule.CaseSensitive = CaseSensitiveCheck.IsChecked == true; rule.Enabled = RuleEnabledCheck.IsChecked == true;
        if (index < 0) { config.Rules.Add(rule); }
        RefreshRules(rule.Id); SetStatus("规则已应用，保存配置后生效", true);
    }

    private void DeleteRule_Click(object sender, RoutedEventArgs e)
    {
        if (RuleList.SelectedIndex < 0) { return; }
        string id = config.Rules[RuleList.SelectedIndex].Id;
        config.Rules.RemoveAt(RuleList.SelectedIndex); config.Automation.TriggerRuleIds.RemoveAll(value => value == id); RefreshRules();
    }

    private void RuleList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RuleList.SelectedIndex is < 0 || RuleList.SelectedIndex >= int.MaxValue || RuleList.SelectedIndex >= config.Rules.Count) { return; }
        LogMonitorRule rule = config.Rules[RuleList.SelectedIndex]; RuleNameBox.Text = rule.Name; RuleExpressionBox.Text = rule.Expression;
        RuleModeBox.SelectedIndex = (int)rule.Mode; CaseSensitiveCheck.IsChecked = rule.CaseSensitive; RuleEnabledCheck.IsChecked = rule.Enabled;
    }

    private void ApplyConfig()
    {
        LogPathBox.Text = config.LogFile; RefreshRules();
        LogMonitorAutomation automation = config.Automation; AutomationEnabledCheck.IsChecked = automation.Enabled; InputTextBox.Text = automation.Text;
        TypeTextCheck.IsChecked = automation.TypeText; PressEnterCheck.IsChecked = automation.PressEnter; StartAtBox.Value = automation.StartAtMatch;
        EveryBox.Value = automation.EveryMatches; MaxExecutionsBox.Value = automation.MaxExecutions; RemoteCheck.IsChecked = automation.RemoteCheckEnabled;
        RemoteUrlBox.Text = automation.RemoteUrl; RemoteKeywordBox.Text = automation.RemoteKeyword; RemoteActionBox.SelectedIndex = (int)automation.RemoteMatchAction;
        SelectTriggerRules(automation.TriggerRuleIds);
    }

    private void CaptureConfig()
    {
        config.LogFile = LogPathBox.Text.Trim();
        config.Automation = new()
        {
            Enabled = AutomationEnabledCheck.IsChecked == true,
            TriggerRuleIds = TriggerRuleList.SelectedItems.Cast<TriggerRuleRow>().Select(row => row.Id).ToList(),
            TargetWindow = WindowBox.SelectedItem is WindowTarget target ? target.Selector : config.Automation.TargetWindow,
            TypeText = TypeTextCheck.IsChecked == true,
            Text = InputTextBox.Text,
            PressEnter = PressEnterCheck.IsChecked == true,
            StartAtMatch = Number(StartAtBox, 1),
            EveryMatches = Number(EveryBox, 1),
            MaxExecutions = Number(MaxExecutionsBox, 0),
            RemoteCheckEnabled = RemoteCheck.IsChecked == true,
            RemoteUrl = RemoteUrlBox.Text.Trim(),
            RemoteKeyword = RemoteKeywordBox.Text,
            RemoteMatchAction = (LogRemoteMatchAction)RemoteActionBox.SelectedIndex
        };
    }

    private void RefreshRules(string? selectedId = null)
    {
        RuleList.ItemsSource = config.Rules.Select(rule => new RuleRow(rule.Enabled ? "启用" : "停用", rule.Name, rule.Expression, ModeName(rule.Mode))).ToArray();
        TriggerRuleList.ItemsSource = config.Rules.Where(rule => rule.Enabled).Select(rule => new TriggerRuleRow(rule.Id, $"{rule.Name} · {rule.Expression}")).ToArray();
        SelectTriggerRules(config.Automation.TriggerRuleIds);
        if (selectedId is not null) { int index = config.Rules.FindIndex(rule => rule.Id == selectedId); if (index >= 0) { RuleList.SelectedIndex = index; } }
    }

    private void SelectTriggerRules(IReadOnlyCollection<string> ids)
    {
        TriggerRuleList.SelectedItems.Clear();
        if (TriggerRuleList.ItemsSource is IEnumerable<TriggerRuleRow> rows)
        {
            foreach (TriggerRuleRow row in rows.Where(row => ids.Contains(row.Id))) { TriggerRuleList.SelectedItems.Add(row); }
        }
    }

    private void Service_MatchFound(object? sender, LogMonitorMatch match) => DispatcherQueue.TryEnqueue(() =>
    {
        matches.Add(MatchRow.From(match)); while (matches.Count > MemoryLimits.RecentMatches) { matches.RemoveAt(0); }
        MatchCountText.Text = $"近期命中 {matches.Count:N0} 条"; if (matches.Count > 0) { MatchList.ScrollIntoView(matches[^1]); }
    });
    private void Service_StatusChanged(object? sender, string status) => DispatcherQueue.TryEnqueue(() => SetStatus(status, true));
    private void Service_AutomationEvent(object? sender, AutomationEvent value) => DispatcherQueue.TryEnqueue(() =>
    {
        eventLogs.Add($"[{DateTimeOffset.Now.ToString("HH:mm:ss", CultureInfo.CurrentCulture)}] 第 {value.MatchCount:N0} 次命中 · {value.Message}"); EventLogBox.Text = eventLogs.SnapshotText(); EventLogBox.Select(EventLogBox.Text.Length, 0);
    });

    private void SetRunning(bool running) { StartButton.IsEnabled = !running; StopButton.IsEnabled = running; Progress.IsActive = running; }
    private void SetStatus(string text, bool success) { StatusText.Text = text; StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush; }
    private async void Page_Unloaded(object sender, RoutedEventArgs e) { if (service.Running) { await service.StopAsync(); SetRunning(false); } }
    public async ValueTask DisposeAsync() { service.MatchFound -= Service_MatchFound; await service.DisposeAsync(); store.Dispose(); }
    private static int Number(NumberBox box, int fallback) => double.IsNaN(box.Value) ? fallback : checked((int)box.Value);
    private static string ModeName(LogMatchMode mode) => mode switch { LogMatchMode.Contains => "包含", LogMatchMode.WholeToken => "词元", _ => "正则" };
    private sealed record RuleRow(string State, string Name, string Expression, string Mode);
    private sealed record TriggerRuleRow(string Id, string Display);
    private sealed record MatchRow(string Time, string Rule, string Line) { public static MatchRow From(LogMonitorMatch match) => new(match.Timestamp.ToString("HH:mm:ss", CultureInfo.CurrentCulture), match.RuleName, match.Line); }
}
