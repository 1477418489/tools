using System.Globalization;
using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace FxTools.App.Pages;

public sealed partial class HttpRequestPage : Page, IDisposable
{
    private static readonly string[] TextFileTypes = [".txt"];
    private static readonly string[] JsonFileTypes = [".json"];
    private readonly HttpTemplateStore templateStore = new();
    private Dictionary<string, HttpTemplate> templates = new(StringComparer.OrdinalIgnoreCase);
    private CancellationTokenSource? requestCancellation;
    private CancellationTokenSource? scheduleCancellation;
    private bool loadedTemplates;

    public HttpRequestPage()
    {
        InitializeComponent();
        MethodBox.ItemsSource = new[] { "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS" };
        ResponseFormatBox.ItemsSource = new[] { "自动", "美化 JSON", "原始内容" };
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (loadedTemplates) { return; }
        loadedTemplates = true;
        try
        {
            templates = await templateStore.LoadAsync();
            RefreshTemplates();
        }
        catch (Exception exception) when (exception is IOException or System.Text.Json.JsonException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e)
    {
        requestCancellation?.Cancel();
        StopSchedule();
    }

    private async void Send_Click(object sender, RoutedEventArgs e) => await SendOnceAsync();
    private void Cancel_Click(object sender, RoutedEventArgs e) => requestCancellation?.Cancel();

    private async Task SendOnceAsync()
    {
        if (requestCancellation is not null) { return; }
        CancellationTokenSource cancellation = new();
        requestCancellation = cancellation;
        SetBusy(true);
        SetStatus("正在发送请求", true);
        try
        {
            HttpRequestResult result = await HttpRequestService.SendAsync(BuildSpec(), cancellation.Token);
            ResponseBodyBox.Text = HttpRequestService.FormatResponseBody(result.Body,
                (ResponseFormat)ResponseFormatBox.SelectedIndex);
            ResponseHeadersBox.Text = result.Headers;
            MetricsText.Text = $"HTTP {result.StatusCode} {result.ReasonPhrase} · {result.Elapsed.TotalMilliseconds:N0} ms · {result.Body.Length:N0} 字符";
            SetStatus($"请求完成: {result.FinalUri}", result.StatusCode is >= 200 and < 400);
        }
        catch (OperationCanceledException) { SetStatus("请求已取消", true); }
        catch (Exception exception) when (exception is ArgumentException or HttpRequestException or IOException or TimeoutException)
        {
            SetStatus(exception.Message, false);
        }
        finally
        {
            cancellation.Dispose();
            if (ReferenceEquals(requestCancellation, cancellation)) { requestCancellation = null; }
            SetBusy(false);
        }
    }

    private async void StartSchedule_Click(object sender, RoutedEventArgs e)
    {
        if (scheduleCancellation is not null) { return; }
        if (double.IsNaN(IntervalBox.Value) || IntervalBox.Value < 1) { SetStatus("定时间隔至少为 1 秒", false); return; }
        scheduleCancellation = new();
        StartScheduleButton.IsEnabled = false;
        StopScheduleButton.IsEnabled = true;
        SetStatus("定时请求已启动", true);
        try
        {
            while (!scheduleCancellation.IsCancellationRequested)
            {
                await SendOnceAsync();
                await Task.Delay(TimeSpan.FromSeconds(IntervalBox.Value), scheduleCancellation.Token);
            }
        }
        catch (OperationCanceledException) { }
        finally { StopSchedule(); }
    }

    private void StopSchedule_Click(object sender, RoutedEventArgs e)
    {
        StopSchedule();
        SetStatus("定时请求已停止", true);
    }

    private void StopSchedule()
    {
        CancellationTokenSource? cancellation = scheduleCancellation;
        scheduleCancellation = null;
        cancellation?.Cancel();
        cancellation?.Dispose();
        StartScheduleButton.IsEnabled = true;
        StopScheduleButton.IsEnabled = false;
    }

    private async void SaveTemplate_Click(object sender, RoutedEventArgs e)
    {
        TextBox nameBox = new() { PlaceholderText = "模板名称", MaxLength = 100 };
        ContentDialog dialog = new()
        {
            XamlRoot = XamlRoot,
            Title = "保存请求模板",
            Content = nameBox,
            PrimaryButtonText = "保存",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || string.IsNullOrWhiteSpace(nameBox.Text)) { return; }
        try
        {
            templates[nameBox.Text.Trim()] = CaptureTemplate();
            await templateStore.SaveAsync(templates);
            RefreshTemplates(nameBox.Text.Trim());
            SetStatus("模板已保存", true);
        }
        catch (Exception exception) when (exception is IOException or InvalidDataException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private async void DeleteTemplate_Click(object sender, RoutedEventArgs e)
    {
        if (TemplateBox.SelectedItem is not string name) { return; }
        templates.Remove(name);
        await templateStore.SaveAsync(templates);
        RefreshTemplates();
        SetStatus("模板已删除", true);
    }

    private void TemplateBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (TemplateBox.SelectedItem is string name && templates.TryGetValue(name, out HttpTemplate? template))
        {
            UrlBox.Text = template.Url;
            MethodBox.SelectedItem = template.Method;
            ContentBox.Text = template.Params;
            HeadersBox.Text = template.Headers;
            if (double.TryParse(template.Interval, NumberStyles.Float, CultureInfo.InvariantCulture, out double interval)) { IntervalBox.Value = interval; }
            if (double.TryParse(template.ReadTimeout, NumberStyles.Float, CultureInfo.InvariantCulture, out double timeout)) { TimeoutBox.Value = timeout; }
        }
    }

    private async void SaveResponse_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(ResponseBodyBox.Text)) { SetStatus("当前没有响应体可保存", false); return; }
        FileSavePicker picker = new() { SuggestedFileName = "response" };
        picker.FileTypeChoices.Add("文本", TextFileTypes);
        picker.FileTypeChoices.Add("JSON", JsonFileTypes);
        InitializeWithWindow.Initialize(picker, App.MainWindowHandle);
        StorageFile? file = await picker.PickSaveFileAsync();
        if (file is not null)
        {
            await FileIO.WriteTextAsync(file, ResponseBodyBox.Text);
            SetStatus("响应体已保存", true);
        }
    }

    private HttpRequestSpec BuildSpec()
    {
        double seconds = double.IsNaN(TimeoutBox.Value) ? 30 : TimeoutBox.Value;
        return new(UrlBox.Text, MethodBox.SelectedItem?.ToString() ?? "GET", ContentBox.Text,
            HeadersBox.Text, TimeSpan.FromSeconds(seconds));
    }

    private HttpTemplate CaptureTemplate() => new()
    {
        Url = UrlBox.Text,
        Method = MethodBox.SelectedItem?.ToString() ?? "GET",
        Params = ContentBox.Text,
        Headers = HeadersBox.Text,
        Interval = IntervalBox.Value.ToString(CultureInfo.InvariantCulture),
        ConnectTimeout = TimeoutBox.Value.ToString(CultureInfo.InvariantCulture),
        ReadTimeout = TimeoutBox.Value.ToString(CultureInfo.InvariantCulture)
    };

    private void RefreshTemplates(string? selected = null)
    {
        TemplateBox.ItemsSource = templates.Keys.Order(StringComparer.CurrentCulture).ToArray();
        TemplateBox.SelectedItem = selected;
    }

    private void SetBusy(bool busy)
    {
        Progress.IsActive = busy;
        SendButton.IsEnabled = !busy;
        CancelButton.IsEnabled = busy;
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush;
    }

    public void Dispose()
    {
        requestCancellation?.Cancel();
        requestCancellation?.Dispose();
        requestCancellation = null;
        StopSchedule();
        templateStore.Dispose();
    }
}
