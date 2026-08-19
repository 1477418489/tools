using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Authentication;
using System.Text;

namespace FxTools.Core.Services;

public static class NetworkQualityService
{
    private const uint StunMagicCookie = 0x2112A442;
    private const int MaxHistory = 240;

    public static async Task<NetworkProbeResult> ProbeAsync(
        NetworkTarget target,
        ProxySettings? proxy,
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(target);
        if (timeout < TimeSpan.FromMilliseconds(250) || timeout > TimeSpan.FromSeconds(30))
        {
            throw new ArgumentOutOfRangeException(nameof(timeout));
        }
        long started = Stopwatch.GetTimestamp();
        try
        {
            string detail = target.Protocol switch
            {
                NetworkProbeProtocol.Http => await ProbeHttpAsync(target, proxy, timeout, cancellationToken).ConfigureAwait(false),
                NetworkProbeProtocol.Tcp => await ProbeTcpAsync(target, proxy, useTls: false, timeout, cancellationToken).ConfigureAwait(false),
                NetworkProbeProtocol.Tls => await ProbeTcpAsync(target, proxy, useTls: true, timeout, cancellationToken).ConfigureAwait(false),
                NetworkProbeProtocol.Stun => await ProbeStunAsync(target, proxy, timeout, cancellationToken).ConfigureAwait(false),
                _ => throw new ArgumentOutOfRangeException(nameof(target))
            };
            return new(DateTimeOffset.Now, true, Stopwatch.GetElapsedTime(started), detail, null);
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception exception) when (exception is IOException
                                          or SocketException
                                          or HttpRequestException
                                          or AuthenticationException)
        {
            return new(DateTimeOffset.Now, false, Stopwatch.GetElapsedTime(started), string.Empty, exception.Message);
        }
    }

    public static NetworkStatistics Calculate(IReadOnlyList<NetworkProbeResult> samples)
    {
        NetworkProbeResult[] recent = samples.TakeLast(MaxHistory).ToArray();
        double[] successful = recent.Where(sample => sample.Success)
            .Select(sample => sample.Elapsed.TotalMilliseconds).Order().ToArray();
        if (recent.Length == 0)
        {
            return new(NetworkQuality.Waiting, 0, 0, double.NaN, double.NaN, double.NaN, double.NaN);
        }
        double availability = successful.Length * 100d / recent.Length;
        double average = successful.Length == 0 ? double.NaN : successful.Average();
        double p95 = successful.Length == 0 ? double.NaN
            : successful[(int)Math.Ceiling(successful.Length * 0.95) - 1];
        double peak = successful.Length == 0 ? double.NaN : successful[^1];
        double jitter = successful.Length < 2 ? 0 : successful.Zip(successful.Skip(1),
            (left, right) => Math.Abs(right - left)).Average();
        NetworkQuality quality = availability switch
        {
            < 20 => NetworkQuality.Offline,
            < 90 => NetworkQuality.Poor,
            < 98 => NetworkQuality.Degraded,
            _ when average <= 100 && jitter <= 30 => NetworkQuality.Excellent,
            _ when average <= 300 && jitter <= 80 => NetworkQuality.Good,
            _ => NetworkQuality.Degraded
        };
        return new(quality, recent.Length, successful.Length, average, p95, peak, jitter);
    }

    private static async Task<string> ProbeHttpAsync(
        NetworkTarget target,
        ProxySettings? proxy,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        Uri uri = BuildHttpUri(target);
        using SocketsHttpHandler handler = new()
        {
            ConnectTimeout = timeout,
            AutomaticDecompression = DecompressionMethods.All,
            UseProxy = proxy is not null,
            Proxy = proxy is null ? null : new WebProxy(proxy.Address)
        };
        using HttpClient client = new(handler) { Timeout = Timeout.InfiniteTimeSpan };
        using CancellationTokenSource timeoutSource = new(timeout);
        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken, timeoutSource.Token);
        using HttpRequestMessage request = new(HttpMethod.Head, uri);
        using HttpResponseMessage response = await client.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, linked.Token).ConfigureAwait(false);
        return $"HTTP {(int)response.StatusCode} · {response.Version}";
    }

    private static async Task<string> ProbeTcpAsync(
        NetworkTarget target,
        ProxySettings? proxy,
        bool useTls,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        using CancellationTokenSource timeoutSource = new(timeout);
        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken, timeoutSource.Token);
        using TcpClient client = new();
        string connectHost = proxy?.Address.Host ?? target.Host;
        int connectPort = proxy?.Address.Port ?? target.Port;
        await client.ConnectAsync(connectHost, connectPort, linked.Token).ConfigureAwait(false);
        Stream stream = client.GetStream();
        if (proxy is not null)
        {
            stream = await EstablishHttpTunnelAsync(stream, target.Host, target.Port, linked.Token).ConfigureAwait(false);
        }
        if (!useTls)
        {
            return proxy is null ? "TCP 连接成功" : $"HTTP 代理 {proxy.Address.Authority} · TCP 隧道";
        }
        using SslStream tls = new(stream, leaveInnerStreamOpen: false);
        await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = target.Host,
            EnabledSslProtocols = System.Security.Authentication.SslProtocols.None,
            CertificateRevocationCheckMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck
        }, linked.Token).ConfigureAwait(false);
        return $"{tls.SslProtocol} · {tls.NegotiatedCipherSuite}";
    }

    private static async Task<Stream> EstablishHttpTunnelAsync(
        Stream stream,
        string host,
        int port,
        CancellationToken cancellationToken)
    {
        byte[] request = Encoding.ASCII.GetBytes(
            $"CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\nProxy-Connection: Keep-Alive\r\n\r\n");
        await stream.WriteAsync(request, cancellationToken).ConfigureAwait(false);
        byte[] response = new byte[8192];
        int used = 0;
        while (used < response.Length)
        {
            int read = await stream.ReadAsync(response.AsMemory(used), cancellationToken).ConfigureAwait(false);
            if (read == 0) { throw new IOException("代理在 CONNECT 响应前断开。"); }
            used += read;
            string text = Encoding.ASCII.GetString(response, 0, used);
            if (text.Contains("\r\n\r\n", StringComparison.Ordinal))
            {
                string status = text.Split("\r\n", 2, StringSplitOptions.None)[0];
                if (!status.Contains(" 200 ", StringComparison.Ordinal))
                {
                    throw new IOException($"代理 CONNECT 失败: {status}");
                }
                return stream;
            }
        }
        throw new IOException("代理 CONNECT 响应过大。");
    }

    private static async Task<string> ProbeStunAsync(
        NetworkTarget target,
        ProxySettings? proxy,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        if (proxy is not null)
        {
            throw new IOException("HTTP 代理不支持 STUN UDP 探测，请使用直连。");
        }
        IPAddress[] addresses = await Dns.GetHostAddressesAsync(target.Host, cancellationToken).ConfigureAwait(false);
        IPAddress address = addresses.FirstOrDefault(value => value.AddressFamily == AddressFamily.InterNetwork)
                            ?? addresses.First();
        using UdpClient udp = new(address.AddressFamily);
        byte[] transaction = RandomNumberGenerator.GetBytes(12);
        byte[] request = new byte[20];
        BinaryPrimitives.WriteUInt16BigEndian(request, 0x0001);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(4), StunMagicCookie);
        transaction.CopyTo(request, 8);
        await udp.SendAsync(request, new IPEndPoint(address, target.Port), cancellationToken).ConfigureAwait(false);
        UdpReceiveResult response = await udp.ReceiveAsync(cancellationToken).AsTask()
            .WaitAsync(timeout, cancellationToken).ConfigureAwait(false);
        IPEndPoint mapping = DecodeStun(response.Buffer, transaction);
        return $"公网映射 {mapping.Address}:{mapping.Port}";
    }

    public static IPEndPoint DecodeStun(ReadOnlySpan<byte> packet, ReadOnlySpan<byte> transaction)
    {
        if (packet.Length < 20 || transaction.Length != 12
            || BinaryPrimitives.ReadUInt16BigEndian(packet) != 0x0101
            || BinaryPrimitives.ReadUInt32BigEndian(packet[4..]) != StunMagicCookie
            || !packet.Slice(8, 12).SequenceEqual(transaction))
        {
            throw new InvalidDataException("STUN 响应格式或事务 ID 无效。");
        }
        int end = 20 + BinaryPrimitives.ReadUInt16BigEndian(packet[2..]);
        if (end > packet.Length) { throw new InvalidDataException("STUN 响应长度不完整。"); }
        int offset = 20;
        while (offset + 4 <= end)
        {
            ushort type = BinaryPrimitives.ReadUInt16BigEndian(packet[offset..]);
            int length = BinaryPrimitives.ReadUInt16BigEndian(packet[(offset + 2)..]);
            int value = offset + 4;
            if (value + length > end) { throw new InvalidDataException("STUN 属性长度无效。"); }
            if (type is 0x0020 or 0x0001 && length >= 8)
            {
                bool xor = type == 0x0020;
                byte family = packet[value + 1];
                int port = BinaryPrimitives.ReadUInt16BigEndian(packet[(value + 2)..]);
                if (xor) { port ^= (int)(StunMagicCookie >> 16); }
                int addressLength = family == 1 ? 4 : family == 2 ? 16 : 0;
                if (addressLength == 0 || length < 4 + addressLength)
                {
                    throw new InvalidDataException("STUN 地址族无效。");
                }
                byte[] addressBytes = packet.Slice(value + 4, addressLength).ToArray();
                if (xor)
                {
                    Span<byte> mask = stackalloc byte[16];
                    BinaryPrimitives.WriteUInt32BigEndian(mask, StunMagicCookie);
                    transaction.CopyTo(mask[4..]);
                    for (int index = 0; index < addressLength; index++) { addressBytes[index] ^= mask[index]; }
                }
                return new(new IPAddress(addressBytes), port);
            }
            offset = value + ((length + 3) & ~3);
        }
        throw new InvalidDataException("STUN 响应未包含公网映射地址。");
    }

    private static Uri BuildHttpUri(NetworkTarget target)
    {
        string path = string.IsNullOrWhiteSpace(target.RequestTarget) ? "/" : target.RequestTarget;
        if (!path.StartsWith('/')) { path = $"/{path}"; }
        return new UriBuilder("https", target.Host, target.Port, path).Uri;
    }
}

public sealed record NetworkTarget(
    string Name,
    NetworkProbeProtocol Protocol,
    string Host,
    int Port,
    string RequestTarget = "/");

public sealed record ProxySettings(Uri Address);

public sealed record NetworkProbeResult(
    DateTimeOffset CapturedAt,
    bool Success,
    TimeSpan Elapsed,
    string Detail,
    string? Error);

public sealed record NetworkStatistics(
    NetworkQuality Quality,
    int Sent,
    int Received,
    double AverageMilliseconds,
    double P95Milliseconds,
    double PeakMilliseconds,
    double JitterMilliseconds)
{
    public double AvailabilityPercent => Sent == 0 ? 0 : Received * 100d / Sent;
}

public enum NetworkProbeProtocol
{
    Http,
    Tcp,
    Tls,
    Stun
}

public enum NetworkQuality
{
    Waiting,
    Excellent,
    Good,
    Degraded,
    Poor,
    Offline
}
