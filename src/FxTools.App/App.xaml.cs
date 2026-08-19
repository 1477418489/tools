using Microsoft.UI.Xaml;
using FxTools.Core.Infrastructure;

namespace FxTools.App;

public partial class App : Application
{
    private MainWindow? mainWindow;

    internal static nint MainWindowHandle { get; set; }
    internal static ApplicationServices Services { get; private set; } = null!;

    public App()
    {
        InitializeComponent();
        UnhandledException += OnUnhandledException;
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        Services = new ApplicationServices();
        mainWindow = new MainWindow(Services);
        mainWindow.Activate();
        IReadOnlyList<string> warnings = await Services.InitializeAsync();
        await mainWindow.ReportInitializationWarningsAsync(warnings);
    }

    private static void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs args)
    {
        System.Diagnostics.Debug.WriteLine(args.Exception);
        try
        {
            AppDataPaths.EnsureDataDirectory();
            File.AppendAllText(
                AppDataPaths.DataFile("crash.log"),
                $"{DateTimeOffset.Now:O}{Environment.NewLine}{args.Exception}{Environment.NewLine}{Environment.NewLine}");
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            System.Diagnostics.Debug.WriteLine(exception);
        }
    }
}
