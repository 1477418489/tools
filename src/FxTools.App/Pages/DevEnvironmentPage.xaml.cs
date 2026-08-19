using System.Runtime.InteropServices;
using System.Text;
using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;

namespace FxTools.App.Pages;

public sealed partial class DevEnvironmentPage : Page, IDisposable
{
    private CancellationTokenSource? inspectionCancellation;
    private EnvironmentReport? report;

    public DevEnvironmentPage()
    {
        InitializeComponent();
        ArchitectureText.Text = $"{RuntimeInformation.OSDescription} · {RuntimeInformation.ProcessArchitecture}";
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (report is null)
        {
            await InspectAsync();
        }
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e) => inspectionCancellation?.Cancel();

    public void Dispose()
    {
        inspectionCancellation?.Cancel();
        inspectionCancellation?.Dispose();
        inspectionCancellation = null;
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await InspectAsync();

    private async Task InspectAsync()
    {
        if (inspectionCancellation is not null)
        {
            return;
        }
        CancellationTokenSource cancellation = new();
        inspectionCancellation = cancellation;
        report = null;
        ResultList.ItemsSource = null;
        SetBusy(true);
        SetStatus("正在并行检查开发工具", true);
        try
        {
            EnvironmentReport result = await DevEnvironmentService.InspectAsync(cancellation.Token);
            report = result;
            ResultList.ItemsSource = result.Results.Select(EnvironmentRow.From).ToArray();
            AvailableText.Text = $"{result.AvailableCount} 项";
            IssueText.Text = $"{result.RequiredIssueCount} 项";
            SetStatus(result.RequiredIssueCount == 0 ? "核心开发环境正常" : "发现核心环境问题",
                result.RequiredIssueCount == 0);
        }
        catch (OperationCanceledException)
        {
            SetStatus("环境检查已取消", true);
        }
        finally
        {
            cancellation.Dispose();
            if (ReferenceEquals(inspectionCancellation, cancellation))
            {
                inspectionCancellation = null;
            }
            SetBusy(false);
        }
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (report is null)
        {
            return;
        }
        StringBuilder text = new("FxTools 开发环境体检");
        foreach (CheckResult result in report.Results)
        {
            text.AppendLine().AppendLine().Append(StatusName(result.Status)).Append("  ").AppendLine(result.Name)
                .Append("版本: ").AppendLine(result.Version)
                .Append("路径: ").AppendLine(string.IsNullOrWhiteSpace(result.Path) ? "--" : result.Path)
                .Append("详情: ").Append(result.Detail);
        }
        DataPackage package = new();
        package.SetText(text.ToString());
        Clipboard.SetContent(package);
        Clipboard.Flush();
        SetStatus("体检报告已复制", true);
    }

    private void SetBusy(bool busy)
    {
        Progress.IsActive = busy;
        RefreshButton.IsEnabled = !busy;
        CopyButton.IsEnabled = !busy && report is not null;
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[
            success ? "SuccessBrush" : "WarningBrush"] as Brush;
    }

    private static string StatusName(CheckStatus status) => status switch
    {
        CheckStatus.Available => "可用",
        CheckStatus.Warning => "需检查",
        CheckStatus.Missing => "未安装",
        CheckStatus.Error => "异常",
        _ => "未知"
    };

    private sealed record EnvironmentRow(string Status, string Name, string Version, string Path, string Detail)
    {
        public static EnvironmentRow From(CheckResult result) => new(
            StatusName(result.Status), result.Name, result.Version,
            string.IsNullOrWhiteSpace(result.Path) ? "--" : result.Path, result.Detail);
    }
}
