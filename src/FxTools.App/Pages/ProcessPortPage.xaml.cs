using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;

namespace FxTools.App.Pages;

public sealed partial class ProcessPortPage : Page, IDisposable
{
    private CancellationTokenSource? refreshCancellation;
    private ProcessPortRow[] allRows = [];
    private bool loadedOnce;

    public ProcessPortPage()
    {
        InitializeComponent();
        ProtocolBox.ItemsSource = new[] { "全部", "TCP", "UDP" };
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (!loadedOnce)
        {
            loadedOnce = true;
            await RefreshAsync();
        }
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e) => refreshCancellation?.Cancel();

    public void Dispose()
    {
        refreshCancellation?.Cancel();
        refreshCancellation?.Dispose();
        refreshCancellation = null;
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await RefreshAsync();

    private async Task RefreshAsync()
    {
        if (refreshCancellation is not null)
        {
            return;
        }
        CancellationTokenSource cancellation = new();
        refreshCancellation = cancellation;
        SetBusy(true);
        SetStatus("正在读取进程和端口快照", true);
        try
        {
            ProcessPortSnapshot snapshot = await ProcessPortService.CaptureAsync(cancellation.Token);
            allRows = snapshot.Entries.Select(ProcessPortRow.From).ToArray();
            ApplyFilter();
            SetStatus($"快照时间 {snapshot.CapturedAtUtc.ToLocalTime():HH:mm:ss}", true);
        }
        catch (OperationCanceledException)
        {
            SetStatus("刷新已取消", true);
        }
        catch (Exception exception) when (exception is IOException or System.ComponentModel.Win32Exception)
        {
            SetStatus(exception.Message, false);
        }
        finally
        {
            cancellation.Dispose();
            if (ReferenceEquals(refreshCancellation, cancellation))
            {
                refreshCancellation = null;
            }
            SetBusy(false);
        }
    }

    private void FilterBox_TextChanged(object sender, TextChangedEventArgs e) => ApplyFilter();
    private void ProtocolBox_SelectionChanged(object sender, SelectionChangedEventArgs e) => ApplyFilter();

    private void ApplyFilter()
    {
        string filter = FilterBox.Text.Trim();
        int protocolIndex = ProtocolBox.SelectedIndex;
        ProcessPortRow[] filtered = allRows.Where(row =>
                (protocolIndex == 0
                 || protocolIndex == 1 && row.Protocol.StartsWith("TCP", StringComparison.Ordinal)
                 || protocolIndex == 2 && row.Protocol.StartsWith("UDP", StringComparison.Ordinal))
                && (filter.Length == 0 || row.SearchText.Contains(filter, StringComparison.OrdinalIgnoreCase)))
            .ToArray();
        EntryList.ItemsSource = filtered;
        CountText.Text = $"{filtered.Length:N0} 条";
        EntryList.SelectedItem = null;
    }

    private void EntryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        ProcessPortRow? row = EntryList.SelectedItem as ProcessPortRow;
        CopyButton.IsEnabled = row is not null;
        TerminateButton.IsEnabled = row is not null && row.ProcessId > 4 && row.ProcessId != Environment.ProcessId;
        SelectedText.Text = row is null ? string.Empty : $"{row.ProcessName} · PID {row.ProcessId} · {row.ExecutablePath}";
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (EntryList.SelectedItem is not ProcessPortRow row)
        {
            return;
        }
        DataPackage package = new();
        package.SetText(row.Details);
        Clipboard.SetContent(package);
        Clipboard.Flush();
        SetStatus("所选端口详情已复制", true);
    }

    private async void Terminate_Click(object sender, RoutedEventArgs e)
    {
        if (EntryList.SelectedItem is not ProcessPortRow row)
        {
            return;
        }
        ContentDialog confirmation = new()
        {
            XamlRoot = XamlRoot,
            Title = "终止进程",
            Content = $"将终止 {row.ProcessName}（PID {row.ProcessId}）。进程身份会在执行前再次校验。",
            PrimaryButtonText = "终止",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Close
        };
        if (await confirmation.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }
        try
        {
            await ProcessPortService.TerminateAsync(row.Identity, force: true);
            SetStatus($"已终止 {row.ProcessName}（PID {row.ProcessId}）", true);
            await RefreshAsync();
        }
        catch (Exception exception) when (exception is InvalidOperationException
                                          or System.ComponentModel.Win32Exception
                                          or ArgumentException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private void SetBusy(bool busy)
    {
        Progress.IsActive = busy;
        RefreshButton.IsEnabled = !busy;
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[
            success ? "SuccessBrush" : "DangerBrush"] as Brush;
    }

    private sealed record ProcessPortRow(
        string Protocol,
        string LocalAddress,
        int LocalPort,
        int ProcessId,
        string ProcessName,
        string RemoteEndpoint,
        string State,
        string ExecutablePath,
        ProcessIdentity Identity)
    {
        public string SearchText => $"{Protocol} {LocalAddress} {LocalPort} {ProcessId} {ProcessName} {RemoteEndpoint} {State} {ExecutablePath}";
        public string Details => $"协议: {Protocol}\n本地: {LocalAddress}:{LocalPort}\n远程: {RemoteEndpoint}\n状态: {State}\n进程: {ProcessName}\nPID: {ProcessId}\n路径: {ExecutablePath}";

        public static ProcessPortRow From(ProcessPortEntry entry) => new(
            entry.Protocol,
            entry.LocalAddress,
            entry.LocalPort,
            entry.ProcessId,
            entry.ProcessName,
            entry.RemotePort is null ? "--" : $"{entry.RemoteAddress}:{entry.RemotePort}",
            entry.State,
            entry.ExecutablePath,
            new(entry.ProcessId, entry.ProcessName, entry.StartedAtUtc));
    }
}
