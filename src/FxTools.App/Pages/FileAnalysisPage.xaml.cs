using System.Globalization;
using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace FxTools.App.Pages;

public sealed partial class FileAnalysisPage : Page, IDisposable
{
    private CancellationTokenSource? analysisCancellation;

    public FileAnalysisPage() => InitializeComponent();

    private async void Choose_Click(object sender, RoutedEventArgs e)
    {
        FileOpenPicker picker = new();
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, App.MainWindowHandle);
        StorageFile? file = await picker.PickSingleFileAsync();
        if (file is null)
        {
            return;
        }
        PathBox.Text = file.Path;
        AnalyzeButton.IsEnabled = true;
        await AnalyzeAsync();
    }

    private async void Analyze_Click(object sender, RoutedEventArgs e) => await AnalyzeAsync();

    private void Cancel_Click(object sender, RoutedEventArgs e) => analysisCancellation?.Cancel();

    private async Task AnalyzeAsync()
    {
        if (string.IsNullOrWhiteSpace(PathBox.Text) || analysisCancellation is not null)
        {
            return;
        }
        CancellationTokenSource cancellation = new();
        analysisCancellation = cancellation;
        SetBusy(true);
        ResetResult();
        SetStatus("正在流式计算哈希并分析文件", true);
        try
        {
            FileAnalysis result = await FileAnalysisService.AnalyzeAsync(
                PathBox.Text, cancellation.Token);
            ApplyResult(result);
            SetStatus($"分析完成，耗时 {result.DurationMilliseconds:N0} ms", true);
        }
        catch (OperationCanceledException)
        {
            SetStatus("分析已取消", true);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            SetStatus(exception.Message, false);
        }
        finally
        {
            cancellation.Dispose();
            if (ReferenceEquals(analysisCancellation, cancellation))
            {
                analysisCancellation = null;
            }
            SetBusy(false);
        }
    }

    private void ApplyResult(FileAnalysis result)
    {
        NameText.Text = System.IO.Path.GetFileName(result.Path);
        SizeText.Text = FormatBytes(result.Size);
        TypeText.Text = result.ContentType;
        EncodingText.Text = result.Encoding;
        ModifiedText.Text = result.ModifiedAtUtc.ToLocalTime().ToString(
            "yyyy-MM-dd HH:mm:ss", CultureInfo.CurrentCulture);
        AccessText.Text = $"{(result.Readable ? "可读" : "不可读")} · {(result.Writable ? "可写" : "只读")}";
        EncodingDetailText.Text = result.EncodingDetail;
        LockText.Text = $"{LockDisplayName(result.LockState)} · {result.LockDetail}";
        Sha256Box.Text = result.Sha256;
        Sha1Box.Text = result.Sha1;
        Md5Box.Text = result.Md5;
    }

    private void ResetResult()
    {
        NameText.Text = SizeText.Text = TypeText.Text = EncodingText.Text = ModifiedText.Text = AccessText.Text = "--";
        EncodingDetailText.Text = "等待分析";
        LockText.Text = "未检测";
        Sha256Box.Text = Sha1Box.Text = Md5Box.Text = string.Empty;
    }

    private void CopySha256_Click(object sender, RoutedEventArgs e) => CopyHash(Sha256Box.Text, "SHA-256");
    private void CopySha1_Click(object sender, RoutedEventArgs e) => CopyHash(Sha1Box.Text, "SHA-1");
    private void CopyMd5_Click(object sender, RoutedEventArgs e) => CopyHash(Md5Box.Text, "MD5");

    private void CopyHash(string value, string name)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            SetStatus($"当前没有可复制的 {name}", false);
            return;
        }
        DataPackage package = new();
        package.SetText(value);
        Clipboard.SetContent(package);
        Clipboard.Flush();
        SetStatus($"{name} 已复制", true);
    }

    private void SetBusy(bool busy)
    {
        Progress.IsActive = busy;
        AnalyzeButton.IsEnabled = !busy && !string.IsNullOrWhiteSpace(PathBox.Text);
        CancelButton.IsEnabled = busy;
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[
            success ? "SuccessBrush" : "DangerBrush"] as Brush;
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e) => analysisCancellation?.Cancel();

    public void Dispose()
    {
        analysisCancellation?.Cancel();
        analysisCancellation?.Dispose();
        analysisCancellation = null;
    }

    private static string LockDisplayName(LockState state) => state switch
    {
        LockState.Available => "未发现占用",
        LockState.Locked => "可能被占用",
        LockState.ReadOnly => "只读或无权限",
        _ => "无法确认"
    };

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024) { return $"{bytes:N0} B"; }
        string[] units = ["KB", "MB", "GB", "TB"];
        double value = bytes;
        int unit = -1;
        do { value /= 1024; unit++; } while (value >= 1024 && unit < units.Length - 1);
        return $"{value:N2} {units[unit]}";
    }
}
