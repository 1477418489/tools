using System.Globalization;
using System.Collections.ObjectModel;
using System.Net.WebSockets;
using FxTools.Core.Infrastructure;
using FxTools.Core.Services;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace FxTools.App.Pages;

public sealed partial class WebSocketPage : Page, IAsyncDisposable
{
    private readonly WebSocketSession session = new();
    private readonly ObservableCollection<MessageRow> rows = [];
    private bool disposed;

    public WebSocketPage()
    {
        InitializeComponent();
        MessageList.ItemsSource = rows;
        session.EntryReceived += Session_EntryReceived;
        session.StateChanged += Session_StateChanged;
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (!Uri.TryCreate(UrlBox.Text, UriKind.Absolute, out Uri? uri))
        {
            SetStatus("请输入有效的 WebSocket 地址", false);
            return;
        }
        SetBusy(true);
        try
        {
            await session.ConnectAsync(uri, HeadersBox.Text);
            SetStatus("连接已建立", true);
        }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException or WebSocketException)
        {
            SetStatus(exception.Message, false);
        }
        finally { SetBusy(false); }
    }

    private async void Disconnect_Click(object sender, RoutedEventArgs e)
    {
        try { await session.DisconnectAsync(); }
        catch (Exception exception) when (exception is WebSocketException or InvalidOperationException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await session.SendTextAsync(MessageBox.Text);
            MessageBox.Text = string.Empty;
        }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException or WebSocketException)
        {
            SetStatus(exception.Message, false);
        }
    }

    private void Clear_Click(object sender, RoutedEventArgs e)
    {
        session.ClearHistory();
        rows.Clear();
        SetStatus("消息历史已清空", true);
    }

    private void Session_EntryReceived(object? sender, WebSocketEntry entry)
    {
        DispatcherQueue.TryEnqueue(DispatcherQueuePriority.Normal, () =>
        {
            if (disposed) { return; }
            rows.Add(MessageRow.From(entry));
            while (rows.Count > MemoryLimits.DisplayLogLines) { rows.RemoveAt(0); }
            if (rows.Count > 0) { MessageList.ScrollIntoView(rows[^1]); }
        });
    }

    private void Session_StateChanged(object? sender, WebSocketState state)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            bool open = state == WebSocketState.Open;
            ConnectButton.IsEnabled = !open;
            DisconnectButton.IsEnabled = open;
            SendButton.IsEnabled = open;
            if (!open && !disposed) { SetStatus("未连接", true); }
        });
    }

    private async void Page_Unloaded(object sender, RoutedEventArgs e)
    {
        if (session.State == WebSocketState.Open)
        {
            await session.DisconnectAsync();
        }
    }

    private void SetBusy(bool busy)
    {
        Progress.IsActive = busy;
        ConnectButton.IsEnabled = !busy;
    }

    private void SetStatus(string text, bool success)
    {
        StatusText.Text = text;
        StatusText.Foreground = Application.Current.Resources[success ? "SuccessBrush" : "DangerBrush"] as Brush;
    }

    public async ValueTask DisposeAsync()
    {
        disposed = true;
        session.EntryReceived -= Session_EntryReceived;
        session.StateChanged -= Session_StateChanged;
        await session.DisposeAsync();
    }

    private sealed record MessageRow(string Time, string Kind, string Text)
    {
        public static MessageRow From(WebSocketEntry entry) => new(
            entry.Timestamp.ToString("HH:mm:ss", CultureInfo.CurrentCulture), entry.Kind switch
            {
                WebSocketEntryKind.Sent => "发送",
                WebSocketEntryKind.Received => "接收",
                WebSocketEntryKind.Error => "错误",
                _ => "系统"
            }, entry.Text);
    }
}
