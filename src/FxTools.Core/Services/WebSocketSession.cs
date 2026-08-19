using System.Buffers;
using System.Net.WebSockets;
using System.Text;
using FxTools.Core.Infrastructure;

namespace FxTools.Core.Services;

public sealed class WebSocketSession : IAsyncDisposable
{
    private const int MaxMessageCharacters = 100_000;
    private readonly BoundedTextBuffer history = new(
        MemoryLimits.DisplayLogLines,
        MemoryLimits.DisplayLogCharacters,
        MemoryLimits.SingleLogCharacters);
    private readonly SemaphoreSlim lifecycleGate = new(1, 1);
    private ClientWebSocket? socket;
    private CancellationTokenSource? receiveCancellation;
    private Task? receiveTask;

    public event EventHandler<WebSocketEntry>? EntryReceived;
    public event EventHandler<WebSocketState>? StateChanged;

    public WebSocketState State => socket?.State ?? WebSocketState.None;
    public IReadOnlyList<string> History => history.Snapshot();

    public async Task ConnectAsync(
        Uri uri,
        string? headers,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(uri);
        if (uri.Scheme is not ("ws" or "wss"))
        {
            throw new ArgumentException("WebSocket 地址必须使用 ws:// 或 wss://。", nameof(uri));
        }
        await lifecycleGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (socket?.State is WebSocketState.Open or WebSocketState.Connecting)
            {
                throw new InvalidOperationException("WebSocket 已连接或正在连接。");
            }
            await DisposeSocketAsync().ConfigureAwait(false);
            ClientWebSocket next = new();
            foreach ((string name, string value) in HttpRequestService.ParseHeaders(headers))
            {
                next.Options.SetRequestHeader(name, value);
            }
            socket = next;
            receiveCancellation = new();
            Append(WebSocketEntryKind.System, $"正在连接 {uri}");
            try
            {
                await next.ConnectAsync(uri, cancellationToken).ConfigureAwait(false);
                RaiseStateChanged(next.State);
                Append(WebSocketEntryKind.System, "连接已建立");
                receiveTask = ReceiveLoopAsync(next, receiveCancellation.Token);
            }
            catch
            {
                await DisposeSocketAsync().ConfigureAwait(false);
                throw;
            }
        }
        finally
        {
            lifecycleGate.Release();
        }
    }

    public async Task SendTextAsync(string message, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(message);
        if (message.Length > MaxMessageCharacters)
        {
            throw new ArgumentException("单条消息不能超过 10 万字符。", nameof(message));
        }
        ClientWebSocket current = socket ?? throw new InvalidOperationException("WebSocket 尚未连接。");
        if (current.State != WebSocketState.Open)
        {
            throw new InvalidOperationException("WebSocket 当前不可发送消息。");
        }
        byte[] bytes = Encoding.UTF8.GetBytes(message);
        await current.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken).ConfigureAwait(false);
        Append(WebSocketEntryKind.Sent, message);
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken = default)
    {
        await lifecycleGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ClientWebSocket? current = socket;
            if (current?.State == WebSocketState.Open)
            {
                await current.CloseOutputAsync(
                    WebSocketCloseStatus.NormalClosure, "FxTools disconnect", cancellationToken)
                    .ConfigureAwait(false);
            }
            await DisposeSocketAsync().ConfigureAwait(false);
            Append(WebSocketEntryKind.System, "连接已断开");
        }
        finally
        {
            lifecycleGate.Release();
        }
    }

    public void ClearHistory() => history.Clear();

    private async Task ReceiveLoopAsync(ClientWebSocket current, CancellationToken cancellationToken)
    {
        byte[] buffer = ArrayPool<byte>.Shared.Rent(16 * 1024);
        try
        {
            while (current.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
            {
                using MemoryStream message = new();
                WebSocketReceiveResult result;
                do
                {
                    result = await current.ReceiveAsync(buffer, cancellationToken).ConfigureAwait(false);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        Append(WebSocketEntryKind.System,
                            $"远端关闭: {result.CloseStatus} {result.CloseStatusDescription}");
                        return;
                    }
                    int remaining = MaxMessageCharacters * 4 + 1 - (int)message.Length;
                    if (remaining <= 0)
                    {
                        throw new InvalidDataException("收到的 WebSocket 消息过大。");
                    }
                    message.Write(buffer, 0, Math.Min(result.Count, remaining));
                }
                while (!result.EndOfMessage);

                string text = result.MessageType == WebSocketMessageType.Text
                    ? Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length)
                    : $"[二进制消息 {message.Length:N0} 字节]";
                if (text.Length > MaxMessageCharacters)
                {
                    text = string.Concat(text.AsSpan(0, MaxMessageCharacters), "\n[消息已截断]");
                }
                Append(WebSocketEntryKind.Received, text);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (Exception exception) when (exception is WebSocketException or IOException or InvalidDataException)
        {
            Append(WebSocketEntryKind.Error, exception.Message);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
            RaiseStateChanged(current.State);
        }
    }

    private void Append(WebSocketEntryKind kind, string text)
    {
        WebSocketEntry entry = new(DateTimeOffset.Now, kind, text);
        string formatted = $"[{entry.Timestamp:HH:mm:ss}] {KindName(kind)}  {text}";
        history.Add(formatted);
        EntryReceived?.Invoke(this, entry);
    }

    private static string KindName(WebSocketEntryKind kind) => kind switch
    {
        WebSocketEntryKind.Sent => "发送",
        WebSocketEntryKind.Received => "接收",
        WebSocketEntryKind.Error => "错误",
        _ => "系统"
    };

    private void RaiseStateChanged(WebSocketState state) => StateChanged?.Invoke(this, state);

    private async Task DisposeSocketAsync()
    {
        CancellationTokenSource? cancellation = receiveCancellation;
        receiveCancellation = null;
        cancellation?.Cancel();
        Task? pending = receiveTask;
        receiveTask = null;
        if (pending is not null && pending.Id != Task.CurrentId)
        {
            try { await pending.ConfigureAwait(false); }
            catch (OperationCanceledException) { }
        }
        cancellation?.Dispose();
        socket?.Dispose();
        socket = null;
        RaiseStateChanged(WebSocketState.Closed);
    }

    public async ValueTask DisposeAsync()
    {
        await lifecycleGate.WaitAsync().ConfigureAwait(false);
        try { await DisposeSocketAsync().ConfigureAwait(false); }
        finally
        {
            lifecycleGate.Release();
            lifecycleGate.Dispose();
        }
    }
}

public sealed record WebSocketEntry(
    DateTimeOffset Timestamp,
    WebSocketEntryKind Kind,
    string Text);

public enum WebSocketEntryKind
{
    System,
    Sent,
    Received,
    Error
}
