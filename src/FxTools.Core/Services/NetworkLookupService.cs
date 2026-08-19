using System.Net;
using System.Net.Sockets;
using System.Text.Json;

namespace FxTools.Core.Services;

public static class NetworkLookupService
{
    private const int MaxResponseBytes = 256 * 1024;
    private static readonly HttpClient Client = new(new SocketsHttpHandler
    {
        AutomaticDecompression = DecompressionMethods.All,
        PooledConnectionLifetime = TimeSpan.FromMinutes(10)
    })
    {
        Timeout = Timeout.InfiniteTimeSpan
    };

    public static async Task<NetworkLookupResult> LookupAsync(
        string input,
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        string host = NormalizeHost(input);
        using CancellationTokenSource timeoutSource = new(timeout);
        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken, timeoutSource.Token);
        IPAddress[] addresses = await Dns.GetHostAddressesAsync(host, linked.Token).ConfigureAwait(false);
        if (addresses.Length == 0)
        {
            throw new IOException("目标没有可查询的 IP 地址。");
        }
        IPAddress selected = addresses.FirstOrDefault(IsPublic) ?? addresses[0];
        if (!IsPublic(selected))
        {
            return LocalResult(input, selected, addresses);
        }
        return await QueryRemoteAsync(input, selected.ToString(), addresses, linked.Token).ConfigureAwait(false);
    }

    public static async Task<NetworkLookupResult> LookupPublicAsync(
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        using CancellationTokenSource timeoutSource = new(timeout);
        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken, timeoutSource.Token);
        return await QueryRemoteAsync("当前公网出口", string.Empty, [], linked.Token).ConfigureAwait(false);
    }

    private static async Task<NetworkLookupResult> QueryRemoteAsync(
        string query,
        string address,
        IReadOnlyList<IPAddress> resolved,
        CancellationToken cancellationToken)
    {
        using HttpRequestMessage request = new(HttpMethod.Get, $"https://ipwho.is/{Uri.EscapeDataString(address)}");
        request.Headers.UserAgent.ParseAdd("FxTools-WinUI/1.0");
        using HttpResponseMessage response = await Client.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"IP 信息服务响应异常: HTTP {(int)response.StatusCode}");
        }
        byte[] bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
        if (bytes.Length > MaxResponseBytes)
        {
            throw new InvalidDataException("IP 信息服务响应过大。");
        }
        using JsonDocument document = JsonDocument.Parse(bytes);
        JsonElement root = document.RootElement;
        if (root.TryGetProperty("success", out JsonElement success) && success.ValueKind == JsonValueKind.False)
        {
            throw new IOException(GetText(root, "message", "IP 信息服务返回失败。"));
        }
        JsonElement connection = root.TryGetProperty("connection", out JsonElement connectionValue)
            ? connectionValue : default;
        JsonElement timezone = root.TryGetProperty("timezone", out JsonElement timezoneValue)
            ? timezoneValue : default;
        return new(
            query,
            GetText(root, "ip"),
            GetText(root, "type"),
            "公网 / 全局地址",
            resolved.Select(value => value.ToString()).ToArray(),
            Join(GetText(root, "country"), GetText(root, "region"), GetText(root, "city"), GetText(root, "postal")),
            Join(GetText(connection, "isp"), GetText(connection, "org"), NormalizeAsn(connection)),
            Join(GetText(timezone, "id"), GetText(timezone, "utc")),
            Coordinate(root, "latitude", "longitude"),
            "ipwho.is",
            "公网归属信息仅供网络诊断参考");
    }

    private static NetworkLookupResult LocalResult(
        string query,
        IPAddress address,
        IReadOnlyList<IPAddress> resolved) => new(
            query,
            address.ToString(),
            address.AddressFamily == AddressFamily.InterNetwork ? "IPv4" : "IPv6",
            Scope(address),
            resolved.Select(value => value.ToString()).ToArray(),
            "暂无数据",
            "本地地址",
            "暂无数据",
            "暂无数据",
            "本地分析",
            "私有、本地和回环地址没有公网归属信息");

    private static string NormalizeHost(string input)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(input);
        string value = input.Trim();
        if (Uri.TryCreate(value, UriKind.Absolute, out Uri? uri))
        {
            return uri.Host;
        }
        int colon = value.LastIndexOf(':');
        return colon > 0 && value.Count(character => character == ':') == 1
            ? value[..colon] : value.Trim('[', ']');
    }

    private static bool IsPublic(IPAddress address) => Scope(address) == "公网 / 全局地址";

    public static string Scope(IPAddress address)
    {
        if (IPAddress.IsLoopback(address)) { return "回环地址"; }
        if (address.AddressFamily == AddressFamily.InterNetwork)
        {
            byte[] bytes = address.GetAddressBytes();
            if (bytes[0] == 10 || bytes[0] == 172 && bytes[1] is >= 16 and <= 31
                || bytes[0] == 192 && bytes[1] == 168) { return "私有地址"; }
            if (bytes[0] == 169 && bytes[1] == 254) { return "链路本地地址"; }
            if (bytes[0] >= 224) { return "保留或组播地址"; }
        }
        else
        {
            if (address.IsIPv6LinkLocal) { return "链路本地地址"; }
            if (address.IsIPv6Multicast) { return "组播地址"; }
            byte first = address.GetAddressBytes()[0];
            if ((first & 0xFE) == 0xFC) { return "唯一本地地址"; }
        }
        return "公网 / 全局地址";
    }

    private static string GetText(JsonElement element, string name, string fallback = "") =>
        element.ValueKind == JsonValueKind.Object
        && element.TryGetProperty(name, out JsonElement value)
        && value.ValueKind is not (JsonValueKind.Null or JsonValueKind.Undefined)
            ? value.ToString().Trim() : fallback;

    private static string NormalizeAsn(JsonElement connection)
    {
        string asn = GetText(connection, "asn");
        return string.IsNullOrEmpty(asn) || asn.StartsWith("AS", StringComparison.OrdinalIgnoreCase)
            ? asn : $"AS{asn}";
    }

    private static string Coordinate(JsonElement root, string latitude, string longitude)
    {
        string left = GetText(root, latitude);
        string right = GetText(root, longitude);
        return string.IsNullOrEmpty(left) || string.IsNullOrEmpty(right) ? "暂无数据" : $"{left}, {right}";
    }

    private static string Join(params string[] values)
    {
        string[] present = values.Where(value => !string.IsNullOrWhiteSpace(value))
            .Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        return present.Length == 0 ? "暂无数据" : string.Join(" / ", present);
    }
}

public sealed record NetworkLookupResult(
    string Query,
    string Ip,
    string Type,
    string Scope,
    IReadOnlyList<string> ResolvedAddresses,
    string Location,
    string Network,
    string TimeZone,
    string Coordinates,
    string DataSource,
    string Note);
