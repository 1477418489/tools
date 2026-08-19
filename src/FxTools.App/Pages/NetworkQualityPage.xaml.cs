using System.Collections.ObjectModel;
using System.Globalization;
using System.Text;
using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;

namespace FxTools.App.Pages;

public sealed partial class NetworkQualityPage : Page, IDisposable
{
    private readonly NetworkQualityStore store = new();
    private readonly ObservableCollection<HistoryRow> historyRows = [];
    private readonly List<NetworkProbeResult> samples = [];
    private List<NetworkTarget> targets = [];
    private CancellationTokenSource? monitorCancellation;
    private bool initialized;

    public NetworkQualityPage()
    {
        InitializeComponent();
        ProtocolBox.ItemsSource = new[] { "HTTP", "TCP", "TLS", "STUN" };
        HistoryList.ItemsSource = historyRows;
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (initialized) { return; }
        initialized = true;
        try
        {
            targets = await store.LoadTargetsAsync();
            NetworkQualitySettings settings = await store.LoadSettingsAsync();
            IntervalBox.Value = settings.IntervalSeconds;
            TimeoutBox.Value = settings.TimeoutSeconds;
            ProxyCheck.IsChecked = settings.UseProxy;
            ProxyBox.Text = settings.ProxyUrl;
            RefreshTargets();
            if (targets.Count > 0) { TargetBox.SelectedIndex = 0; }
        }
        catch (Exception exception) when (exception is IOException or System.Text.Json.JsonException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private async void Start_Click(object sender, RoutedEventArgs e)
    {
        if (monitorCancellation is not null) { return; }
        NetworkTarget target;
        ProxySettings? proxy;
        try { target = CaptureTarget(); proxy = CaptureProxy(); }
        catch (Exception exception) when (exception is ArgumentException or InvalidDataException) { SetStatus(exception.Message, false); return; }
        monitorCancellation = new();
        samples.Clear(); historyRows.Clear(); UpdateStatistics(); SetRunning(true);
        await SaveSettingsAsync();
        try
        {
            while (!monitorCancellation.IsCancellationRequested)
            {
                Progress.IsActive = true;
                NetworkProbeResult result = await NetworkQualityService.ProbeAsync(
                    target, proxy, TimeSpan.FromSeconds(TimeoutBox.Value), monitorCancellation.Token);
                samples.Add(result);
                if (samples.Count > 240) { samples.RemoveAt(0); }
                historyRows.Add(HistoryRow.From(result));
                if (historyRows.Count > 240) { historyRows.RemoveAt(0); }
                UpdateStatistics();
                SetStatus(result.Success ? result.Detail : result.Error ?? "探测失败", result.Success);
                Progress.IsActive = false;
                await Task.Delay(TimeSpan.FromSeconds(IntervalBox.Value), monitorCancellation.Token);
            }
        }
        catch (OperationCanceledException) { }
        finally { StopMonitor(); }
    }

    private void Stop_Click(object sender, RoutedEventArgs e) { StopMonitor(); SetStatus("监测已停止", true); }

    private void StopMonitor()
    {
        CancellationTokenSource? cancellation = monitorCancellation;
        monitorCancellation = null;
        cancellation?.Cancel(); cancellation?.Dispose();
        SetRunning(false); Progress.IsActive = false;
    }

    private async void SaveTarget_Click(object sender, RoutedEventArgs e)
    {
        TextBox nameBox = new() { PlaceholderText = "目标名称", Text = TargetBox.SelectedItem?.ToString() ?? string.Empty };
        ContentDialog dialog = new() { XamlRoot = XamlRoot, Title = "保存监测目标", Content = nameBox, PrimaryButtonText = "保存", CloseButtonText = "取消" };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || string.IsNullOrWhiteSpace(nameBox.Text)) { return; }
        try
        {
            NetworkTarget value = CaptureTarget() with { Name = nameBox.Text.Trim() };
            int index = targets.FindIndex(target => string.Equals(target.Name, value.Name, StringComparison.OrdinalIgnoreCase));
            if (index >= 0) { targets[index] = value; } else { targets.Add(value); }
            await store.SaveTargetsAsync(targets); RefreshTargets(value.Name); SetStatus("目标已保存", true);
        }
        catch (Exception exception) when (exception is ArgumentException or IOException) { SetStatus(exception.Message, false); }
    }

    private async void DeleteTarget_Click(object sender, RoutedEventArgs e)
    {
        if (TargetBox.SelectedIndex < 0) { return; }
        targets.RemoveAt(TargetBox.SelectedIndex); await store.SaveTargetsAsync(targets); RefreshTargets(); SetStatus("目标已删除", true);
    }

    private void TargetBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (TargetBox.SelectedIndex is < 0 || TargetBox.SelectedIndex >= int.MaxValue || TargetBox.SelectedIndex >= targets.Count) { return; }
        NetworkTarget target = targets[TargetBox.SelectedIndex];
        ProtocolBox.SelectedIndex = (int)target.Protocol; HostBox.Text = target.Host; PortBox.Value = target.Port; PathBox.Text = target.RequestTarget;
    }

    private void CopyReport_Click(object sender, RoutedEventArgs e)
    {
        NetworkStatistics stats = NetworkQualityService.Calculate(samples);
        StringBuilder report = new StringBuilder("FxTools 网络质量报告\n")
            .Append("目标: ").Append(TargetBox.SelectedItem).Append('\n')
            .Append("质量: ").Append(QualityName(stats.Quality)).Append('\n')
            .Append("可用率: ").Append(stats.AvailabilityPercent.ToString("N1", CultureInfo.CurrentCulture)).Append("%\n")
            .Append("平均/P95/峰值/抖动: ").Append(Format(stats.AverageMilliseconds)).Append(" / ").Append(Format(stats.P95Milliseconds)).Append(" / ").Append(Format(stats.PeakMilliseconds)).Append(" / ").Append(Format(stats.JitterMilliseconds));
        DataPackage package = new(); package.SetText(report.ToString()); Clipboard.SetContent(package); Clipboard.Flush(); SetStatus("报告已复制", true);
    }

    private NetworkTarget CaptureTarget()
    {
        if (string.IsNullOrWhiteSpace(HostBox.Text) || double.IsNaN(PortBox.Value)) { throw new ArgumentException("主机和端口不能为空。"); }
        return new(TargetBox.SelectedItem?.ToString() ?? "未命名目标", (NetworkProbeProtocol)ProtocolBox.SelectedIndex,
            HostBox.Text.Trim(), checked((int)PortBox.Value), PathBox.Text);
    }

    private ProxySettings? CaptureProxy()
    {
        if (ProxyCheck.IsChecked != true) { return null; }
        if (!Uri.TryCreate(ProxyBox.Text, UriKind.Absolute, out Uri? uri) || uri.Scheme is not ("http" or "https")) { throw new ArgumentException("代理地址必须是 HTTP URL。"); }
        return new(uri);
    }

    private async Task SaveSettingsAsync() => await store.SaveSettingsAsync(new()
    {
        IntervalSeconds = IntervalBox.Value,
        TimeoutSeconds = TimeoutBox.Value,
        UseProxy = ProxyCheck.IsChecked == true,
        ProxyUrl = ProxyBox.Text
    });

    private void RefreshTargets(string? selected = null)
    {
        TargetBox.ItemsSource = targets.Select(target => target.Name).ToArray();
        if (selected is not null) { TargetBox.SelectedItem = selected; }
    }

    private void UpdateStatistics()
    {
        NetworkStatistics stats = NetworkQualityService.Calculate(samples);
        QualityText.Text = QualityName(stats.Quality); AvailabilityText.Text = stats.Sent == 0 ? "--" : $"{stats.AvailabilityPercent:N1}% ({stats.Received}/{stats.Sent})";
        AverageText.Text = $"{Format(stats.AverageMilliseconds)} / {Format(stats.P95Milliseconds)}";
        JitterText.Text = $"{Format(stats.PeakMilliseconds)} / {Format(stats.JitterMilliseconds)}";
    }

    private void SetRunning(bool running) { StartButton.IsEnabled = !running; StopButton.IsEnabled = running; }
    private void SetStatus(string text, bool success) { StatusText.Text = text; StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush; }
    private void Page_Unloaded(object sender, RoutedEventArgs e) => StopMonitor();
    public void Dispose() { StopMonitor(); store.Dispose(); }
    private static string Format(double value) => double.IsNaN(value) ? "--" : $"{value:N1} ms";
    private static string QualityName(NetworkQuality quality) => quality switch { NetworkQuality.Excellent => "优秀", NetworkQuality.Good => "良好", NetworkQuality.Degraded => "一般", NetworkQuality.Poor => "较差", NetworkQuality.Offline => "离线", _ => "等待" };
    private sealed record HistoryRow(string Time, string State, string Elapsed, string Detail)
    {
        public static HistoryRow From(NetworkProbeResult result) => new(result.CapturedAt.ToString("HH:mm:ss", CultureInfo.CurrentCulture), result.Success ? "成功" : "失败", $"{result.Elapsed.TotalMilliseconds:N0} ms", result.Success ? result.Detail : result.Error ?? string.Empty);
    }
}
