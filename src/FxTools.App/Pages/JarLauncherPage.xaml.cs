using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace FxTools.App.Pages;

public sealed partial class JarLauncherPage : Page, IDisposable
{
    private readonly JarProjectStore store = new();
    private readonly JarLauncherManager manager = new();
    private List<JarProject> projects = [];
    private CancellationTokenSource? operationCancellation;
    private bool initialized;

    public JarLauncherPage() => InitializeComponent();

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        if (initialized) { return; }
        initialized = true;
        try { projects = await store.LoadAsync(); RefreshProjects(); SetStatus("项目配置已加载", true); }
        catch (Exception exception) when (exception is IOException or System.Text.Json.JsonException) { SetStatus(exception.Message, false); }
    }

    private void New_Click(object sender, RoutedEventArgs e) { ProjectList.SelectedItem = null; ClearEditor(); }

    private async void Save_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            JarProject value = Capture(); int index = ProjectList.SelectedIndex;
            if (index >= 0) { value.Id = projects[index].Id; projects[index] = value; }
            else { value.Id = projects.Count == 0 ? 1 : projects.Max(project => project.Id) + 1; projects.Add(value); }
            await store.SaveAsync(projects); RefreshProjects(value.Id); SetStatus("项目已保存", true);
        }
        catch (Exception exception) when (exception is ArgumentException or IOException or InvalidDataException) { SetStatus(exception.Message, false); }
    }

    private async void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (ProjectList.SelectedIndex < 0) { return; }
        projects.RemoveAt(ProjectList.SelectedIndex); await store.SaveAsync(projects); RefreshProjects(); ClearEditor();
    }

    private async void PickSourceJar_Click(object sender, RoutedEventArgs e)
    {
        FileOpenPicker picker = new(); picker.FileTypeFilter.Add(".jar"); InitializeWithWindow.Initialize(picker, App.MainWindowHandle);
        StorageFile? file = await picker.PickSingleFileAsync(); if (file is not null) { SourceJarBox.Text = file.Path; if (string.IsNullOrWhiteSpace(TargetJarBox.Text)) { TargetJarBox.Text = file.Path; } }
    }

    private async void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (operationCancellation is not null) { return; }
        CancellationTokenSource cancellation = new(); operationCancellation = cancellation; SetBusy(true);
        try { await JarLauncherManager.CopyArtifactsAsync(Capture(), cancellation.Token); SetStatus("JAR 与 Lib 已复制", true); }
        catch (OperationCanceledException) { SetStatus("复制已取消", true); }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or InvalidDataException) { SetStatus(exception.Message, false); }
        finally { cancellation.Dispose(); if (ReferenceEquals(operationCancellation, cancellation)) { operationCancellation = null; } SetBusy(false); }
    }

    private async void Start_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            JarProject project = SelectedOrCaptured(); int port = checked((int)PortBox.Value);
            JarSessionInfo result = await manager.StartAsync(project, port, ProfileBox.Text); LogPathText.Text = result.LogPath;
            SetStatus($"项目已启动，PID {result.ProcessId}", true); RefreshState(project, port);
        }
        catch (Exception exception) when (exception is IOException or InvalidOperationException or ArgumentException or System.ComponentModel.Win32Exception) { SetStatus(exception.Message, false); }
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (ProjectList.SelectedIndex < 0) { return; }
        JarProject project = projects[ProjectList.SelectedIndex]; int port = checked((int)PortBox.Value);
        ContentDialog dialog = new() { XamlRoot = XamlRoot, Title = "停止 JAR 项目", Content = "将再次验证 PID、启动时间和端口归属，然后终止项目进程树。", PrimaryButtonText = "停止", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) { return; }
        try { await manager.StopAsync(project, port); SetStatus("项目已停止", true); RefreshState(project, port); }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception or ArgumentException) { SetStatus(exception.Message, false); }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => operationCancellation?.Cancel();
    private void OpenFolder_Click(object sender, RoutedEventArgs e) { try { JarLauncherManager.OpenFolder(SelectedOrCaptured()); } catch (Exception exception) { SetStatus(exception.Message, false); } }
    private void OpenLog_Click(object sender, RoutedEventArgs e) { try { JarProject project = SelectedOrCaptured(); JarLauncherManager.OpenLog(project, checked((int)PortBox.Value)); } catch (Exception exception) { SetStatus(exception.Message, false); } }

    private void ProjectList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ProjectList.SelectedIndex is < 0 || ProjectList.SelectedIndex >= int.MaxValue || ProjectList.SelectedIndex >= projects.Count) { return; }
        JarProject project = projects[ProjectList.SelectedIndex]; Apply(project); RefreshState(project, project.DefaultPort);
    }

    private void RefreshState(JarProject project, int port)
    {
        try
        {
            JarRunState state = manager.CaptureState(project, port); RunStateText.Text = StateName(state); StopButton.IsEnabled = state is JarRunState.Running or JarRunState.Starting;
            LogPathText.Text = manager.GetLogPath(project.Id) ?? JarLauncherManager.ResolveLogPath(project, port);
        }
        catch (Exception exception) when (exception is System.ComponentModel.Win32Exception or InvalidOperationException) { RunStateText.Text = "状态未知"; SetStatus(exception.Message, false); }
    }

    private JarProject SelectedOrCaptured() { JarProject value = Capture(); if (ProjectList.SelectedIndex >= 0) { value.Id = projects[ProjectList.SelectedIndex].Id; } return value; }
    private JarProject Capture()
    {
        if (string.IsNullOrWhiteSpace(NameBox.Text) || string.IsNullOrWhiteSpace(SourceJarBox.Text) || string.IsNullOrWhiteSpace(TargetJarBox.Text) || double.IsNaN(PortBox.Value)) { throw new ArgumentException("项目名称、源 JAR、目标 JAR 和端口不能为空。"); }
        return new() { Id = 1, Name = NameBox.Text.Trim(), SourceJar = SourceJarBox.Text.Trim(), TargetJar = TargetJarBox.Text.Trim(), SourceLib = SourceLibBox.Text.Trim(), LibTarget = TargetLibBox.Text.Trim(), DefaultPort = checked((int)PortBox.Value), DefaultProfile = ProfileBox.Text.Trim(), JvmOpts = JvmOptionsBox.Text, OtherOpts = OtherOptionsBox.Text };
    }
    private void Apply(JarProject value) { NameBox.Text = value.Name; SourceJarBox.Text = value.SourceJar; TargetJarBox.Text = value.TargetJar; SourceLibBox.Text = value.SourceLib; TargetLibBox.Text = value.LibTarget; PortBox.Value = value.DefaultPort; ProfileBox.Text = value.DefaultProfile; JvmOptionsBox.Text = value.JvmOpts; OtherOptionsBox.Text = value.OtherOpts; }
    private void ClearEditor() { NameBox.Text = SourceJarBox.Text = TargetJarBox.Text = SourceLibBox.Text = TargetLibBox.Text = ProfileBox.Text = JvmOptionsBox.Text = OtherOptionsBox.Text = string.Empty; PortBox.Value = 8080; RunStateText.Text = "未选择"; LogPathText.Text = "--"; StopButton.IsEnabled = false; }
    private void RefreshProjects(int? selectedId = null) { ProjectList.ItemsSource = projects.Select(project => new ProjectRow(project.Name, $":{project.DefaultPort}")).ToArray(); if (selectedId is not null) { int index = projects.FindIndex(project => project.Id == selectedId); if (index >= 0) { ProjectList.SelectedIndex = index; } } }
    private void SetBusy(bool busy) { Progress.IsActive = busy; CopyButton.IsEnabled = !busy; StartButton.IsEnabled = !busy; CancelButton.IsEnabled = busy; }
    private void SetStatus(string text, bool success) { StatusText.Text = text; StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush; }
    private void Page_Unloaded(object sender, RoutedEventArgs e) => operationCancellation?.Cancel();
    public void Dispose() { operationCancellation?.Cancel(); operationCancellation?.Dispose(); operationCancellation = null; store.Dispose(); }
    private static string StateName(JarRunState state) => state switch { JarRunState.Starting => "启动中", JarRunState.Running => "运行中", JarRunState.Occupied => "端口被占用", _ => "已停止" };
    private sealed record ProjectRow(string Name, string Port);
}
