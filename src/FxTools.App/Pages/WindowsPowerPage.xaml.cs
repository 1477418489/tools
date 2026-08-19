using System.Globalization;
using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace FxTools.App.Pages;

public sealed partial class WindowsPowerPage : Page
{
    private bool initialized;
    public WindowsPowerPage()
    {
        InitializeComponent(); ActionBox.ItemsSource = new[] { "关机", "重启", "休眠" };
        ScheduleDate.Date = DateTimeOffset.Now.AddDays(1); ScheduleTime.Time = DateTime.Now.TimeOfDay;
    }
    private async void Page_Loaded(object sender, RoutedEventArgs e) { if (initialized) { return; } initialized = true; await RefreshTasksAsync(); await InspectAsync(); }
    private async void Shutdown_Click(object sender, RoutedEventArgs e) => await ConfirmImmediateAsync(PowerAction.Shutdown);
    private async void Restart_Click(object sender, RoutedEventArgs e) => await ConfirmImmediateAsync(PowerAction.Restart);
    private async void Hibernate_Click(object sender, RoutedEventArgs e) => await ConfirmImmediateAsync(PowerAction.Hibernate);
    private async void Sleep_Click(object sender, RoutedEventArgs e) => await ConfirmImmediateAsync(PowerAction.Sleep);

    private async Task ConfirmImmediateAsync(PowerAction action)
    {
        ContentDialog dialog = new() { XamlRoot = XamlRoot, Title = $"立即{ActionName(action)}", Content = "该操作会影响当前 Windows 会话，请确认已保存工作。", PrimaryButtonText = ActionName(action), CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) { return; }
        try { await WindowsPowerService.ExecuteImmediateAsync(action); }
        catch (Exception exception) when (exception is IOException or System.ComponentModel.Win32Exception) { SetStatus(exception.Message, false); }
    }

    private async void SchedulePower_Click(object sender, RoutedEventArgs e)
    {
        try { DateTime when = ScheduledTime(); await WindowsPowerService.SchedulePowerAsync(when, (PowerAction)ActionBox.SelectedIndex); SetStatus("电源计划已创建", true); await RefreshTasksAsync(); }
        catch (Exception exception) when (exception is ArgumentException or IOException or TimeoutException) { SetStatus(exception.Message, false); }
    }
    private async void ScheduleWake_Click(object sender, RoutedEventArgs e)
    {
        try { await WindowsPowerService.ScheduleWakeAsync(ScheduledTime()); SetStatus("唤醒计划已创建", true); await RefreshTasksAsync(); }
        catch (Exception exception) when (exception is ArgumentException or IOException or TimeoutException) { SetStatus(exception.Message, false); }
    }
    private async void CancelPower_Click(object sender, RoutedEventArgs e) { try { await WindowsPowerService.CancelPowerAsync(); SetStatus("电源计划已取消", true); await RefreshTasksAsync(); } catch (Exception exception) { SetStatus(exception.Message, false); } }
    private async void CancelWake_Click(object sender, RoutedEventArgs e) { try { await WindowsPowerService.CancelWakeAsync(); SetStatus("唤醒计划已取消", true); await RefreshTasksAsync(); } catch (Exception exception) { SetStatus(exception.Message, false); } }
    private async void RefreshTasks_Click(object sender, RoutedEventArgs e) => await RefreshTasksAsync();
    private async Task RefreshTasksAsync()
    {
        try
        {
            ScheduledPowerTask power = await WindowsPowerService.QueryTaskAsync(WindowsPowerService.PowerTaskName);
            ScheduledPowerTask wake = await WindowsPowerService.QueryTaskAsync(WindowsPowerService.WakeTaskName);
            PowerTaskText.Text = power.Exists ? $"电源计划: {power.ScheduledFor?.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.CurrentCulture)} · {ActionName(power.Action ?? PowerAction.Shutdown)}" : "电源计划: 无";
            WakeTaskText.Text = wake.Exists ? $"唤醒计划: {wake.ScheduledFor?.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.CurrentCulture)}" : "唤醒计划: 无";
        }
        catch (Exception exception) when (exception is IOException or TimeoutException) { SetStatus(exception.Message, false); }
    }
    private async void Inspect_Click(object sender, RoutedEventArgs e) => await InspectAsync();
    private async Task InspectAsync()
    {
        Progress.IsActive = true; InspectButton.IsEnabled = false;
        try
        {
            PowerDiagnostics result = await WindowsPowerService.InspectAsync(); DeviceText.Text = $"{result.Manufacturer} {result.Model}".Trim(); OsText.Text = result.OperatingSystem;
            BiosText.Text = $"{result.FirmwareType} · {result.BiosVendor} {result.BiosVersion} · {result.BiosDate}"; SupplyText.Text = result.PowerSupply; DiagnosticsBox.Text = result.Details; SetStatus("只读诊断完成", true);
        }
        catch (Exception exception) when (exception is IOException or TimeoutException) { SetStatus(exception.Message, false); }
        finally { Progress.IsActive = false; InspectButton.IsEnabled = true; }
    }
    private DateTime ScheduledTime() => ScheduleDate.Date.LocalDateTime.Date + ScheduleTime.Time;
    private void SetStatus(string text, bool success) { StatusText.Text = text; StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush; }
    private static string ActionName(PowerAction action) => action switch { PowerAction.Shutdown => "关机", PowerAction.Restart => "重启", PowerAction.Hibernate => "休眠", _ => "睡眠" };
}
