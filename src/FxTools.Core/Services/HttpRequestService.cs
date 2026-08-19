using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using FxTools.Core.Infrastructure;

namespace FxTools.Core.Services;

public static partial class HttpRequestService
{
    static HttpRequestService() => Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);

    private static readonly SocketsHttpHandler Handler = new()
    {
        AutomaticDecompression = DecompressionMethods.All,
        AllowAutoRedirect = true,
        MaxAutomaticRedirections = 8,
        PooledConnectionLifetime = TimeSpan.FromMinutes(10),
        PooledConnectionIdleTimeout = TimeSpan.FromMinutes(2),
        MaxConnectionsPerServer = 16,
        ConnectTimeout = TimeSpan.FromSeconds(30)
    };
    private static readonly HttpClient Client = new(Handler)
    {
        Timeout = Timeout.InfiniteTimeSpan,
        DefaultRequestVersion = HttpVersion.Version20,
        DefaultVersionPolicy = HttpVersionPolicy.RequestVersionOrLower
    };

    public static async Task<HttpRequestResult> SendAsync(
        HttpRequestSpec spec,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(spec);
        ValidateTimeout(spec.Timeout);
        HttpMethod method = new(spec.Method.Trim().ToUpperInvariant());
        Uri uri = BuildUri(spec.Url, method, spec.Content);
        using HttpRequestMessage request = new(method, uri);
        request.Headers.UserAgent.ParseAdd("FxTools-WinUI/1.0");
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

        IReadOnlyList<KeyValuePair<string, string>> headers = ParseHeaders(spec.Headers);
        bool hasContent = method == HttpMethod.Post || method == HttpMethod.Put || method.Method == "PATCH";
        if (hasContent)
        {
            request.Content = new StringContent(spec.Content ?? string.Empty, Encoding.UTF8, "application/json");
        }
        foreach ((string name, string value) in headers)
        {
            if (!request.Headers.TryAddWithoutValidation(name, value))
            {
                request.Content ??= new ByteArrayContent([]);
                if (!request.Content.Headers.TryAddWithoutValidation(name, value))
                {
                    throw new ArgumentException($"无效的请求头: {name}", nameof(spec));
                }
            }
        }

        using CancellationTokenSource timeout = new(spec.Timeout);
        using CancellationTokenSource linked = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken, timeout.Token);
        long started = Stopwatch.GetTimestamp();
        try
        {
            using HttpResponseMessage response = await Client.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, linked.Token).ConfigureAwait(false);
            string body = await ReadBoundedAsync(response.Content, linked.Token).ConfigureAwait(false);
            StringBuilder responseHeaders = new();
            foreach ((string name, IEnumerable<string> values) in response.Headers.Concat(response.Content.Headers))
            {
                responseHeaders.Append(name).Append(": ").AppendJoin("; ", values).AppendLine();
            }
            return new(
                (int)response.StatusCode,
                response.ReasonPhrase ?? string.Empty,
                Stopwatch.GetElapsedTime(started),
                responseHeaders.ToString(),
                body,
                response.RequestMessage?.RequestUri ?? uri);
        }
        catch (OperationCanceledException exception) when (timeout.IsCancellationRequested
                                                            && !cancellationToken.IsCancellationRequested)
        {
            throw new TimeoutException($"请求超过 {spec.Timeout.TotalSeconds:0.#} 秒。", exception);
        }
    }

    public static IReadOnlyList<KeyValuePair<string, string>> ParseHeaders(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return [];
        }
        List<KeyValuePair<string, string>> headers = [];
        string[] lines = text.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries);
        for (int index = 0; index < lines.Length; index++)
        {
            string line = lines[index].Trim();
            int separator = line.IndexOf(':');
            string name = separator > 0 ? line[..separator].Trim() : string.Empty;
            if (separator < 1 || !HeaderName().IsMatch(name))
            {
                throw new ArgumentException($"第 {index + 1} 行请求头格式无效，应为 名称: 值。", nameof(text));
            }
            headers.Add(KeyValuePair.Create(name, line[(separator + 1)..].Trim()));
        }
        return headers;
    }

    public static string FormatResponseBody(string body, ResponseFormat format)
    {
        if (format == ResponseFormat.Raw || string.IsNullOrWhiteSpace(body))
        {
            return body;
        }
        string trimmed = body.Trim();
        bool likelyJson = trimmed.StartsWith('{') && trimmed.EndsWith('}')
                          || trimmed.StartsWith('[') && trimmed.EndsWith(']');
        if (format == ResponseFormat.Auto && !likelyJson)
        {
            return body;
        }
        try
        {
            using JsonDocument document = JsonDocument.Parse(body, new JsonDocumentOptions { MaxDepth = 128 });
            return JsonSerializer.Serialize(document.RootElement, HttpJson.Options);
        }
        catch (JsonException)
        {
            return body;
        }
    }

    private static Uri BuildUri(string url, HttpMethod method, string? content)
    {
        if (!Uri.TryCreate(url.Trim(), UriKind.Absolute, out Uri? uri)
            || uri.Scheme is not ("http" or "https"))
        {
            throw new ArgumentException("请求地址必须是有效的 HTTP 或 HTTPS URL。", nameof(url));
        }
        if (method != HttpMethod.Get && method != HttpMethod.Head || string.IsNullOrWhiteSpace(content))
        {
            return uri;
        }
        string query = string.Join('&', content.Split('&', StringSplitOptions.TrimEntries)
            .Select(pair =>
            {
                int separator = pair.IndexOf('=');
                return separator < 0
                    ? Uri.EscapeDataString(pair)
                    : $"{Uri.EscapeDataString(pair[..separator])}={Uri.EscapeDataString(pair[(separator + 1)..])}";
            }));
        UriBuilder builder = new(uri)
        {
            Query = string.IsNullOrEmpty(uri.Query)
                ? query
                : $"{uri.Query.TrimStart('?')}&{query}"
        };
        return builder.Uri;
    }

    private static async Task<string> ReadBoundedAsync(HttpContent content, CancellationToken cancellationToken)
    {
        await using Stream stream = await content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        Encoding encoding = GetResponseEncoding(content.Headers.ContentType?.CharSet);
        using StreamReader reader = new(stream, encoding, detectEncodingFromByteOrderMarks: true, 8192, leaveOpen: false);
        char[] buffer = new char[8192];
        StringBuilder body = new(Math.Min(
            content.Headers.ContentLength is > 0 and <= MemoryLimits.HttpBodyCharacters
                ? (int)content.Headers.ContentLength.Value
                : 8192,
            MemoryLimits.HttpBodyCharacters));
        bool truncated = false;
        while (true)
        {
            int read = await reader.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (read == 0) { break; }
            int remaining = MemoryLimits.HttpBodyCharacters - body.Length;
            if (read > remaining)
            {
                if (remaining > 0) { body.Append(buffer, 0, remaining); }
                truncated = true;
                break;
            }
            body.Append(buffer, 0, read);
        }
        if (truncated) { body.AppendLine().Append("[响应体过大，已截断]"); }
        return body.ToString();
    }

    private static Encoding GetResponseEncoding(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) { return Encoding.UTF8; }
        try { return Encoding.GetEncoding(name.Trim().Trim('"')); }
        catch (ArgumentException) { return Encoding.UTF8; }
    }

    private static void ValidateTimeout(TimeSpan timeout)
    {
        if (timeout < TimeSpan.FromMilliseconds(250) || timeout > TimeSpan.FromMinutes(5))
        {
            throw new ArgumentOutOfRangeException(nameof(timeout), "超时时间必须在 250 毫秒到 5 分钟之间。");
        }
    }

    [GeneratedRegex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$", RegexOptions.CultureInvariant)]
    private static partial Regex HeaderName();
}

internal static class HttpJson
{
    internal static readonly JsonSerializerOptions Options = new() { WriteIndented = true };
}

public sealed record HttpRequestSpec(
    string Url,
    string Method,
    string? Content,
    string? Headers,
    TimeSpan Timeout);

public sealed record HttpRequestResult(
    int StatusCode,
    string ReasonPhrase,
    TimeSpan Elapsed,
    string Headers,
    string Body,
    Uri FinalUri);

public enum ResponseFormat
{
    Auto,
    PrettyJson,
    Raw
}
