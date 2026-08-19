using System.Runtime.InteropServices;
using FxTools.Core.Services;
using FxTools.Core.Windows;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using WinRT.Interop;
using FxTools.App.Pages;

namespace FxTools.App;

[System.Diagnostics.CodeAnalysis.SuppressMessage(
    "Design",
    "CA1001:Types that own disposable fields should be disposable",
    Justification = "WinUI Window resources are released by the Closed lifecycle handler.")]
public sealed partial class MainWindow : Window
{
    private const int SwHide = 0;
    private const int SwRestore = 9;

    private readonly ApplicationServices services;
    private readonly Dictionary<string, FrameworkElement> pages = [];
    private readonly SemaphoreSlim dialogGate = new(1, 1);
    private readonly List<string> shellWarnings = [];
    private AppWindow? appWindow;
    private WindowsSystemTrayService? trayService;
    private bool forceExit;
    private bool disposed;

    internal MainWindow(ApplicationServices services)
    {
        this.services = services;
        InitializeComponent();
        Closed += MainWindow_Closed;
        ConfigureWindow();
        services.Reminders.ReminderDueAsync = ShowReminderAsync;
        SelectFirstTool();
    }

    private async void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        if (disposed)
        {
            return;
        }
        disposed = true;
        trayService?.Dispose();
        trayService = null;
        foreach (FrameworkElement page in pages.Values)
        {
            if (page is IAsyncDisposable asyncDisposable)
            {
                await asyncDisposable.DisposeAsync();
            }
            else if (page is IDisposable disposable)
            {
                disposable.Dispose();
            }
        }
        pages.Clear();
        await services.DisposeAsync();
        dialogGate.Dispose();
    }

    private void ConfigureWindow()
    {
        nint windowHandle = WindowNative.GetWindowHandle(this);
        App.MainWindowHandle = windowHandle;
        WindowId windowId = Win32Interop.GetWindowIdFromWindow(windowHandle);
        appWindow = AppWindow.GetFromWindowId(windowId);
        appWindow.Resize(new Windows.Graphics.SizeInt32(1320, 820));
        appWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.png"));
        appWindow.Closing += AppWindow_Closing;
        try
        {
            trayService = new WindowsSystemTrayService(windowHandle, RestoreWindow, ExitApplication);
            trayService.Initialize(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        }
        catch (Exception exception) when (exception is ArgumentException
                                          or PlatformNotSupportedException
                                          or System.ComponentModel.Win32Exception)
        {
            trayService?.Dispose();
            trayService = null;
            shellWarnings.Add($"系统托盘初始化失败，关闭到托盘已停用: {exception.Message}");
        }
    }

    private void AppWindow_Closing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (!forceExit && services.Settings.Current.CloseToTray && trayService?.IsAvailable == true)
        {
            args.Cancel = true;
            _ = NativeMethods.ShowWindow(App.MainWindowHandle, SwHide);
        }
    }

    private void RestoreWindow()
    {
        _ = NativeMethods.ShowWindow(App.MainWindowHandle, SwRestore);
        Activate();
        _ = NativeMethods.SetForegroundWindow(App.MainWindowHandle);
    }

    private void ExitApplication()
    {
        forceExit = true;
        Close();
    }

    private void SelectFirstTool()
    {
        NavigationViewItem firstItem = Navigation.MenuItems
            .OfType<NavigationViewItem>()
            .First(item => item.Tag?.ToString() == "app-launcher");
        Navigation.SelectedItem = firstItem;
        ShowModule(firstItem);
    }

    private void Navigation_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.IsSettingsSelected)
        {
            if (!pages.TryGetValue("settings", out FrameworkElement? settingsPage))
            {
                settingsPage = new SettingsPage(services.Settings, () => trayService?.IsAvailable == true);
                pages["settings"] = settingsPage;
            }
            PageHost.Content = settingsPage;
            return;
        }

        if (args.SelectedItemContainer is NavigationViewItem item)
        {
            ShowModule(item);
        }
    }

    private void ToolSearch_TextChanged(AutoSuggestBox sender, AutoSuggestBoxTextChangedEventArgs args)
    {
        if (args.Reason != AutoSuggestionBoxTextChangeReason.UserInput)
        {
            return;
        }

        string query = sender.Text.Trim();
        sender.ItemsSource = Navigation.MenuItems
            .OfType<NavigationViewItem>()
            .Select(static item => item.Content?.ToString())
            .Where(name => !string.IsNullOrWhiteSpace(name)
                           && (query.Length == 0
                               || name.Contains(query, StringComparison.OrdinalIgnoreCase)))
            .Take(12)
            .ToArray();
    }

    private void ToolSearch_SuggestionChosen(
        AutoSuggestBox sender,
        AutoSuggestBoxSuggestionChosenEventArgs args)
    {
        _ = sender;
        ToolSearch.Text = args.SelectedItem?.ToString() ?? string.Empty;
    }

    private void ToolSearch_QuerySubmitted(AutoSuggestBox sender, AutoSuggestBoxQuerySubmittedEventArgs args)
    {
        string name = args.ChosenSuggestion?.ToString() ?? args.QueryText.Trim();
        NavigationViewItem? item = Navigation.MenuItems
            .OfType<NavigationViewItem>()
            .FirstOrDefault(candidate => string.Equals(
                candidate.Content?.ToString(), name, StringComparison.OrdinalIgnoreCase));
        if (item is null)
        {
            return;
        }

        Navigation.SelectedItem = item;
        ShowModule(item);
        sender.Text = string.Empty;
    }

    private void ShowModule(NavigationViewItem item)
    {
        string tag = item.Tag?.ToString() ?? string.Empty;
        if (!pages.TryGetValue(tag, out FrameworkElement? page))
        {
            page = tag switch
            {
                "base64" => new Base64Page(),
                "data-format" => new DataFormatterPage(),
                "string-tools" => new StringToolsPage(),
                "file-analysis" => new FileAnalysisPage(),
                "dev-environment" => new DevEnvironmentPage(),
                "process-port" => new ProcessPortPage(),
                "http-request" => new HttpRequestPage(),
                "websocket" => new WebSocketPage(),
                "network-tools" => new NetworkToolsPage(),
                "network-quality" => new NetworkQualityPage(),
                "keep-alive" => new KeepAlivePage(),
                "log-monitor" => new LogMonitorPage(),
                "app-launcher" => new AppLauncherPage(),
                "jar-launcher" => new JarLauncherPage(),
                "windows-power" => new WindowsPowerPage(),
                "reminders" => new RemindersPage(services.Reminders),
                _ => throw new InvalidOperationException($"未知的功能模块: {tag}")
            };
            pages[tag] = page;
        }
        PageHost.Content = page;
    }

    internal async Task ReportInitializationWarningsAsync(IReadOnlyList<string> warnings)
    {
        string[] messages = shellWarnings.Concat(warnings)
            .Where(static value => !string.IsNullOrWhiteSpace(value)).ToArray();
        if (messages.Length == 0 || disposed)
        {
            return;
        }

        await dialogGate.WaitAsync();
        try
        {
            ContentDialog dialog = new()
            {
                XamlRoot = Navigation.XamlRoot,
                Title = "部分功能未能初始化",
                Content = string.Join(Environment.NewLine, messages),
                CloseButtonText = "知道了"
            };
            _ = await dialog.ShowAsync();
        }
        finally
        {
            dialogGate.Release();
        }
    }

    private Task<ReminderAction> ShowReminderAsync(
        MemoReminder reminder,
        CancellationToken cancellationToken)
    {
        TaskCompletionSource<ReminderAction> completion = new(
            TaskCreationOptions.RunContinuationsAsynchronously);
        bool queued = DispatcherQueue.TryEnqueue(async () =>
        {
            try
            {
                await dialogGate.WaitAsync(cancellationToken);
                try
                {
                    RestoreWindow();
                    if (services.Settings.Current.ReminderSoundEnabled)
                    {
                        _ = NativeMethods.MessageBeep(0x00000030);
                    }

                    TextBlock content = new()
                    {
                        Text = reminder.Content,
                        TextWrapping = TextWrapping.Wrap,
                        MaxWidth = 520
                    };
                    ContentDialog dialog = new()
                    {
                        XamlRoot = Navigation.XamlRoot,
                        Title = reminder.ScheduleMode == ReminderScheduleMode.AtTime
                            ? "定时闹钟"
                            : "周期提醒",
                        Content = content,
                        PrimaryButtonText = "完成",
                        SecondaryButtonText = "稍后 5 分钟",
                        CloseButtonText = "关闭并稍后提醒",
                        DefaultButton = ContentDialogButton.Primary
                    };
                    using CancellationTokenRegistration registration = cancellationToken.Register(
                        () => DispatcherQueue.TryEnqueue(dialog.Hide));
                    ContentDialogResult result = await dialog.ShowAsync();
                    completion.TrySetResult(result == ContentDialogResult.Primary
                        ? ReminderAction.Complete
                        : ReminderAction.SnoozeFiveMinutes);
                }
                finally
                {
                    dialogGate.Release();
                }
            }
            catch (OperationCanceledException)
            {
                completion.TrySetCanceled(cancellationToken);
            }
            catch (Exception exception)
            {
                completion.TrySetException(exception);
            }
        });
        if (!queued)
        {
            completion.TrySetResult(ReminderAction.SnoozeFiveMinutes);
        }
        return completion.Task;
    }

    private static class NativeMethods
    {
        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool ShowWindow(nint window, int command);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool SetForegroundWindow(nint window);

        [DllImport("user32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool MessageBeep(uint type);
    }
}
