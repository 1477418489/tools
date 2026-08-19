using System.Diagnostics;
using FxTools.Core.Infrastructure;
using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;
using CoreAppDataPaths = FxTools.Core.Infrastructure.AppDataPaths;

namespace FxTools.App.Pages;

public sealed partial class SettingsPage : Page
{
    private readonly AppSettingsService settings;
    private readonly Func<bool> trayAvailable;
    private bool applying;
    private bool initialized;

    internal SettingsPage(AppSettingsService settings, Func<bool> trayAvailable)
    {
        this.settings = settings;
        this.trayAvailable = trayAvailable;
        InitializeComponent();
    }

    private void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (initialized)
        {
            return;
        }
        initialized = true;
        DataPathBox.Text = CoreAppDataPaths.DataDirectory;
        ApplySettings();
        if (!trayAvailable())
        {
            CloseToTrayToggle.IsEnabled = false;
            SetStatus("系统托盘不可用，关闭到托盘设置不会生效", false);
        }
    }

    private async void Setting_Toggled(object sender, RoutedEventArgs e)
    {
        if (applying || !initialized)
        {
            return;
        }
        try
        {
            SetControlsEnabled(false);
            await settings.UpdateAsync(new AppSettings
            {
                CloseToTray = CloseToTrayToggle.IsOn,
                StartWithWindows = StartWithWindowsToggle.IsOn,
                ReminderSoundEnabled = ReminderSoundToggle.IsOn
            });
            SetStatus("设置已保存", true);
        }
        catch (Exception exception) when (exception is IOException
                                          or UnauthorizedAccessException
                                          or InvalidOperationException
                                          or PlatformNotSupportedException)
        {
            ApplySettings();
            SetStatus(exception.Message, false);
        }
        finally
        {
            SetControlsEnabled(true);
        }
    }

    private void OpenDataFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            CoreAppDataPaths.EnsureDataDirectory();
            _ = Process.Start(new ProcessStartInfo
            {
                FileName = CoreAppDataPaths.DataDirectory,
                UseShellExecute = true
            });
            SetStatus("已打开数据目录", true);
        }
        catch (Exception exception) when (exception is IOException
                                          or UnauthorizedAccessException
                                          or System.ComponentModel.Win32Exception)
        {
            SetStatus(exception.Message, false);
        }
    }

    private async void Backup_Click(object sender, RoutedEventArgs e)
    {
        FileSavePicker picker = new()
        {
            SuggestedFileName = $"FxTools-backup-{DateTime.Now:yyyyMMdd-HHmmss}"
        };
        picker.FileTypeChoices.Add("ZIP 备份", [".zip"]);
        InitializeWithWindow.Initialize(picker, App.MainWindowHandle);
        StorageFile? file = await picker.PickSaveFileAsync();
        if (file is null)
        {
            return;
        }

        try
        {
            BackupButton.IsEnabled = false;
            Progress.Visibility = Visibility.Visible;
            BackupResult result = await AppDataBackupService.ExportAsync(file.Path);
            SetStatus($"已备份 {result.FileCount:N0} 个文件，共 {FormatBytes(result.UncompressedBytes)}", true);
        }
        catch (Exception exception) when (exception is IOException
                                          or UnauthorizedAccessException
                                          or InvalidDataException)
        {
            SetStatus(exception.Message, false);
        }
        finally
        {
            Progress.Visibility = Visibility.Collapsed;
            BackupButton.IsEnabled = true;
        }
    }

    private void ApplySettings()
    {
        applying = true;
        try
        {
            AppSettings current = settings.Current;
            CloseToTrayToggle.IsOn = current.CloseToTray;
            StartWithWindowsToggle.IsOn = current.StartWithWindows;
            ReminderSoundToggle.IsOn = current.ReminderSoundEnabled;
        }
        finally
        {
            applying = false;
        }
    }

    private void SetControlsEnabled(bool enabled)
    {
        CloseToTrayToggle.IsEnabled = enabled && trayAvailable();
        StartWithWindowsToggle.IsEnabled = enabled;
        ReminderSoundToggle.IsEnabled = enabled;
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush;
    }

    private static string FormatBytes(long bytes)
    {
        string[] units = ["B", "KiB", "MiB", "GiB"];
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }
        return $"{value:N1} {units[unit]}";
    }
}
