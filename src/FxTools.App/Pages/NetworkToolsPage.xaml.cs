using FxTools.Core.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace FxTools.App.Pages;

public sealed partial class NetworkToolsPage : Page, IDisposable
{
    private CancellationTokenSource? lookupCancellation;

    public NetworkToolsPage() => InitializeComponent();

    private async void Lookup_Click(object sender, RoutedEventArgs e) => await LookupAsync(publicAddress: false);
    private async void Public_Click(object sender, RoutedEventArgs e) => await LookupAsync(publicAddress: true);

    private async Task LookupAsync(bool publicAddress)
    {
        if (lookupCancellation is not null) { return; }
        CancellationTokenSource cancellation = new();
        lookupCancellation = cancellation;
        SetBusy(true);
        SetStatus("正在解析并查询网络信息", true);
        try
        {
            TimeSpan timeout = TimeSpan.FromSeconds(double.IsNaN(TimeoutBox.Value) ? 8 : TimeoutBox.Value);
            NetworkLookupResult result = publicAddress
                ? await NetworkLookupService.LookupPublicAsync(timeout, cancellation.Token)
                : await NetworkLookupService.LookupAsync(TargetBox.Text, timeout, cancellation.Token);
            ApplyResult(result);
            SetStatus(result.Note, true);
        }
        catch (OperationCanceledException) { SetStatus("查询已取消", true); }
        catch (Exception exception) when (exception is ArgumentException or IOException or HttpRequestException or System.Net.Sockets.SocketException)
        {
            SetStatus(exception.Message, false);
        }
        finally
        {
            cancellation.Dispose();
            if (ReferenceEquals(lookupCancellation, cancellation)) { lookupCancellation = null; }
            SetBusy(false);
        }
    }

    private void ApplyResult(NetworkLookupResult result)
    {
        QueryText.Text = result.Query;
        IpText.Text = $"{result.Ip} · {result.Type}";
        ScopeText.Text = result.Scope;
        AddressesText.Text = result.ResolvedAddresses.Count == 0 ? result.Ip : string.Join(" · ", result.ResolvedAddresses);
        LocationText.Text = result.Location;
        NetworkText.Text = result.Network;
        ZoneText.Text = $"{result.TimeZone} · {result.Coordinates}";
        SourceText.Text = result.DataSource;
    }

    private void SetBusy(bool busy)
    {
        Progress.IsActive = busy;
        LookupButton.IsEnabled = !busy;
        PublicButton.IsEnabled = !busy;
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush;
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e) => lookupCancellation?.Cancel();

    public void Dispose()
    {
        lookupCancellation?.Cancel();
        lookupCancellation?.Dispose();
        lookupCancellation = null;
    }
}
