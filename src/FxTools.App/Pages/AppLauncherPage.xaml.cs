using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace FxTools.App.Pages;

public sealed partial class AppLauncherPage : Page, IDisposable
{
    private readonly AppLauncherStore store = new();
    private readonly AppLauncherManager manager = new();
    private List<AppInfo> apps = [];
    private List<AppRunState> states = [];
    private CancellationTokenSource? batchCancellation;
    private bool initialized;

    public AppLauncherPage() => InitializeComponent();

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (initialized) { return; }
        initialized = true;
        try
        {
            apps = await store.LoadAppsAsync(); AppLauncherSettings settings = await store.LoadSettingsAsync();
            DelayBox.Value = settings.LaunchDelayMillis / 1000d; RefreshStates(); SetStatus("启动项已加载", true);
        }
        catch (Exception exception) when (exception is IOException or System.Text.Json.JsonException) { SetStatus(exception.Message, false); }
    }

    private async void Add_Click(object sender, RoutedEventArgs e)
    {
        FileOpenPicker picker = new(); picker.FileTypeFilter.Add(".exe"); picker.FileTypeFilter.Add(".com"); picker.FileTypeFilter.Add(".bat"); picker.FileTypeFilter.Add(".cmd"); picker.FileTypeFilter.Add(".ps1"); picker.FileTypeFilter.Add(".lnk");
        InitializeWithWindow.Initialize(picker, App.MainWindowHandle); IReadOnlyList<StorageFile> selected = await picker.PickMultipleFilesAsync();
        foreach (StorageFile file in selected)
        {
            if (apps.Any(app => string.Equals(app.AppPath, file.Path, StringComparison.OrdinalIgnoreCase))) { continue; }
            apps.Add(new() { AppPath = file.Path, ProcessName = AppLauncherManager.InferProcessName(file.Path) });
        }
        await SaveAppsAsync(); RefreshStates();
    }

    private async void Remove_Click(object sender, RoutedEventArgs e)
    {
        if (AppList.SelectedIndex < 0) { return; }
        apps.RemoveAt(AppList.SelectedIndex); await SaveAppsAsync(); RefreshStates();
    }

    private void Refresh_Click(object sender, RoutedEventArgs e) => RefreshStates();

    private async void LaunchSelected_Click(object sender, RoutedEventArgs e)
    {
        if (AppList.SelectedIndex < 0) { return; }
        await LaunchAsync([apps[AppList.SelectedIndex]]);
    }

    private async void LaunchAll_Click(object sender, RoutedEventArgs e) => await LaunchAsync(apps);

    private async Task LaunchAsync(List<AppInfo> selected)
    {
        if (batchCancellation is not null || selected.Count == 0) { return; }
        CancellationTokenSource cancellation = new(); batchCancellation = cancellation; SetBusy(true);
        try
        {
            IReadOnlyList<LaunchResult> results = await manager.LaunchBatchAsync(selected, TimeSpan.FromSeconds(DelayBox.Value), cancellation.Token);
            SetStatus($"已启动 {results.Count:N0} 个程序", true);
        }
        catch (OperationCanceledException) { SetStatus("批量启动已取消", true); }
        catch (Exception exception) when (exception is IOException or InvalidOperationException or System.ComponentModel.Win32Exception) { SetStatus(exception.Message, false); }
        finally { cancellation.Dispose(); if (ReferenceEquals(batchCancellation, cancellation)) { batchCancellation = null; } SetBusy(false); RefreshStates(); }
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (AppList.SelectedIndex < 0) { return; }
        AppRunState state = states[AppList.SelectedIndex];
        ContentDialog dialog = new() { XamlRoot = XamlRoot, Title = "停止应用", Content = $"将终止 {state.App.ProcessName} 的唯一识别进程及其子进程。", PrimaryButtonText = "停止", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) { return; }
        try { await manager.StopAsync(state); SetStatus("进程已停止", true); RefreshStates(); }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception or ArgumentException) { SetStatus(exception.Message, false); }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => batchCancellation?.Cancel();

    private void AppList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        int index = AppList.SelectedIndex; bool selected = index >= 0 && index < apps.Count;
        PathBox.Text = selected ? apps[index].AppPath : string.Empty; ProcessNameBox.Text = selected ? apps[index].ProcessName : string.Empty;
        LaunchSelectedButton.IsEnabled = selected && batchCancellation is null;
        StopButton.IsEnabled = selected && states[index].State is AppProcessState.RunningManaged or AppProcessState.RunningExternal;
    }

    private async void ProcessNameBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (AppList.SelectedIndex < 0 || string.IsNullOrWhiteSpace(ProcessNameBox.Text)) { return; }
        apps[AppList.SelectedIndex].ProcessName = ProcessNameBox.Text.Trim(); await SaveAppsAsync(); RefreshStates();
    }

    private async void DelayBox_ValueChanged(NumberBox sender, NumberBoxValueChangedEventArgs args)
    {
        if (!initialized || double.IsNaN(args.NewValue)) { return; }
        try { await store.SaveSettingsAsync(new() { LaunchDelayMillis = checked((int)(args.NewValue * 1000)) }); }
        catch (IOException exception) { SetStatus(exception.Message, false); }
    }

    private async Task SaveAppsAsync()
    {
        try { await store.SaveAppsAsync(apps); SetStatus("启动项已保存", true); }
        catch (IOException exception) { SetStatus(exception.Message, false); }
    }

    private void RefreshStates()
    {
        states = manager.CaptureStates(apps).ToList();
        AppList.ItemsSource = states.Select(state => new AppRow(StateName(state.State), state.App.ProcessName, state.App.AppPath,
            state.Processes.Count == 0 ? "--" : string.Join(", ", state.Processes.Select(process => $"PID {process.ProcessId}")))).ToArray();
        AppList.SelectedItem = null;
    }

    private void SetBusy(bool busy) { Progress.IsActive = busy; LaunchAllButton.IsEnabled = !busy; LaunchSelectedButton.IsEnabled = !busy && AppList.SelectedIndex >= 0; CancelButton.IsEnabled = busy; }
    private void SetStatus(string text, bool success) { StatusText.Text = text; StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush; }
    private void Page_Unloaded(object sender, RoutedEventArgs e) => batchCancellation?.Cancel();
    public void Dispose() { batchCancellation?.Cancel(); batchCancellation?.Dispose(); batchCancellation = null; store.Dispose(); }
    private static string StateName(AppProcessState state) => state switch { AppProcessState.RunningManaged => "运行中", AppProcessState.RunningExternal => "外部运行", AppProcessState.Ambiguous => "多个同名", _ => "已停止" };
    private sealed record AppRow(string State, string ProcessName, string Path, string Detail);
}
