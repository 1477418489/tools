using System.Globalization;
using FxTools.Core.Infrastructure;
using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace FxTools.App.Pages;

public sealed partial class KeepAlivePage : Page, IAsyncDisposable
{
    private readonly KeepAliveManager manager = new();
    private readonly BoundedTextBuffer logs = new(MemoryLimits.DisplayLogLines, MemoryLimits.DisplayLogCharacters, MemoryLimits.SingleLogCharacters);
    private List<KeepAliveConfig> configs = [];
    private bool initialized;

    public KeepAlivePage()
    {
        InitializeComponent();
        MethodBox.ItemsSource = new[] { "HTTP 访问", "Ping" };
        UnitBox.ItemsSource = new[] { "分钟", "小时", "天" };
        manager.ProbeCompleted += Manager_ProbeCompleted;
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (initialized) { return; }
        initialized = true;
        try
        {
            configs = await manager.LoadAsync();
            manager.Apply(configs); RefreshList(); SetStatus("配置已加载", true);
        }
        catch (Exception exception) when (exception is IOException or System.Text.Json.JsonException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private void New_Click(object sender, RoutedEventArgs e)
    {
        ConfigList.SelectedItem = null; DomainBox.Text = string.Empty; MethodBox.SelectedIndex = 0;
        MinBox.Value = 5; MaxBox.Value = 10; UnitBox.SelectedIndex = 0; EnabledCheck.IsChecked = true;
    }

    private async void Save_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            KeepAliveConfig value = Capture();
            int existing = configs.FindIndex(config => string.Equals(config.Domain, value.Domain, StringComparison.OrdinalIgnoreCase));
            if (existing >= 0) { configs[existing] = value; } else { configs.Add(value); }
            await manager.SaveAsync(configs); manager.Apply(configs); RefreshList(value.Domain); SetStatus("保活配置已保存并应用", true);
        }
        catch (Exception exception) when (exception is ArgumentException or IOException or InvalidDataException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private async void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (ConfigList.SelectedIndex < 0) { return; }
        string domain = configs[ConfigList.SelectedIndex].Domain;
        configs.RemoveAt(ConfigList.SelectedIndex); manager.Stop(domain); await manager.SaveAsync(configs); RefreshList(); SetStatus("配置已删除", true);
    }

    private async void Test_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            KeepAliveEvent result = await manager.ProbeOnceAsync(Capture());
            AppendLog(result); SetStatus(result.Detail, result.Success);
        }
        catch (Exception exception) when (exception is ArgumentException or IOException) { SetStatus(exception.Message, false); }
    }

    private void ConfigList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ConfigList.SelectedIndex is < 0 || ConfigList.SelectedIndex >= int.MaxValue || ConfigList.SelectedIndex >= configs.Count) { return; }
        KeepAliveConfig config = configs[ConfigList.SelectedIndex];
        DomainBox.Text = config.Domain; EnabledCheck.IsChecked = config.Enabled; MethodBox.SelectedIndex = (int)config.Method;
        MinBox.Value = config.MinInterval; MaxBox.Value = config.MaxInterval; UnitBox.SelectedIndex = (int)config.Unit;
    }

    private KeepAliveConfig Capture()
    {
        if (string.IsNullOrWhiteSpace(DomainBox.Text) || double.IsNaN(MinBox.Value) || double.IsNaN(MaxBox.Value)) { throw new ArgumentException("域名和间隔不能为空。"); }
        return new()
        {
            Domain = DomainBox.Text.Trim(),
            Enabled = EnabledCheck.IsChecked == true,
            Method = (KeepAliveMethod)MethodBox.SelectedIndex,
            MinInterval = checked((int)MinBox.Value),
            MaxInterval = checked((int)MaxBox.Value),
            Unit = (IntervalUnit)UnitBox.SelectedIndex
        };
    }

    private void Manager_ProbeCompleted(object? sender, KeepAliveEvent result)
    {
        DispatcherQueue.TryEnqueue(() => { AppendLog(result); ActiveText.Text = $"运行中 {manager.ActiveCount:N0} 项"; });
    }

    private void AppendLog(KeepAliveEvent result)
    {
        logs.Add($"[{result.Timestamp.ToString("HH:mm:ss", CultureInfo.CurrentCulture)}] {(result.Success ? "成功" : "失败")} {result.Domain} · {result.Detail} · {result.Elapsed.TotalMilliseconds:N0} ms");
        LogBox.Text = logs.SnapshotText(); LogBox.Select(LogBox.Text.Length, 0);
    }

    private void RefreshList(string? selected = null)
    {
        ConfigList.ItemsSource = configs.Select(config => new ConfigRow(config.Domain, config.Method == KeepAliveMethod.HTTP ? "HTTP" : "Ping",
            $"{config.MinInterval}-{config.MaxInterval} {UnitName(config.Unit)}", config.Enabled ? "启用" : "停用")).ToArray();
        if (selected is not null)
        {
            int index = configs.FindIndex(config => string.Equals(config.Domain, selected, StringComparison.OrdinalIgnoreCase));
            if (index >= 0) { ConfigList.SelectedIndex = index; }
        }
        ActiveText.Text = $"运行中 {manager.ActiveCount:N0} 项";
    }

    private void SetStatus(string text, bool success) { StatusText.Text = text; StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush; }
    private static string UnitName(IntervalUnit unit) => unit switch { IntervalUnit.MINUTES => "分钟", IntervalUnit.HOURS => "小时", _ => "天" };
    public async ValueTask DisposeAsync() { manager.ProbeCompleted -= Manager_ProbeCompleted; await manager.DisposeAsync(); }
    private sealed record ConfigRow(string Domain, string Method, string Interval, string State);
}
