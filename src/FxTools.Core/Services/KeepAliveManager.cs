using System.Collections.Concurrent;
using System.Net.NetworkInformation;
using FxTools.Core.Infrastructure;

namespace FxTools.Core.Services;

public sealed class KeepAliveManager : IAsyncDisposable
{
    private readonly ConcurrentDictionary<string, CancellationTokenSource> active =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly HttpClient client = new(new SocketsHttpHandler
    {
        AutomaticDecompression = System.Net.DecompressionMethods.All,
        PooledConnectionLifetime = TimeSpan.FromMinutes(10),
        PooledConnectionIdleTimeout = TimeSpan.FromMinutes(2)
    })
    { Timeout = TimeSpan.FromSeconds(10) };
    private readonly AtomicJsonStore store;
    private readonly string path;

    public KeepAliveManager(string? path = null)
    {
        this.path = path ?? AppDataPaths.DataFile("keepAlive.json");
        store = new(this.path);
    }

    public event EventHandler<KeepAliveEvent>? ProbeCompleted;

    public int ActiveCount => active.Count;

    public async Task<List<KeepAliveConfig>> LoadAsync(CancellationToken cancellationToken = default)
    {
        List<KeepAliveConfig> configs = await store.LoadAsync(() => new List<KeepAliveConfig>(), cancellationToken)
            .ConfigureAwait(false);
        Validate(configs);
        return configs;
    }

    public async Task SaveAsync(IReadOnlyList<KeepAliveConfig> configs, CancellationToken cancellationToken = default)
    {
        Validate(configs);
        if (File.Exists(path)) { _ = await LoadAsync(cancellationToken).ConfigureAwait(false); }
        await store.SaveAsync(configs, cancellationToken).ConfigureAwait(false);
    }

    public void Apply(IReadOnlyList<KeepAliveConfig> configs)
    {
        Validate(configs);
        HashSet<string> desired = configs.Where(config => config.Enabled)
            .Select(config => config.Domain).ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (string running in active.Keys.Where(domain => !desired.Contains(domain)))
        {
            Stop(running);
        }
        foreach (KeepAliveConfig config in configs.Where(config => config.Enabled))
        {
            if (active.ContainsKey(config.Domain)) { continue; }
            CancellationTokenSource cancellation = new();
            if (active.TryAdd(config.Domain, cancellation))
            {
                _ = RunLoopAsync(config, cancellation);
            }
            else { cancellation.Dispose(); }
        }
    }

    public void Stop(string domain)
    {
        if (active.TryRemove(domain, out CancellationTokenSource? cancellation))
        {
            cancellation.Cancel();
            cancellation.Dispose();
        }
    }

    public Task<KeepAliveEvent> ProbeOnceAsync(
        KeepAliveConfig config,
        CancellationToken cancellationToken = default) => ProbeAsync(config, cancellationToken);

    private async Task RunLoopAsync(KeepAliveConfig config, CancellationTokenSource source)
    {
        try
        {
            await Task.Delay(Random.Shared.Next(0, 5000), source.Token).ConfigureAwait(false);
            while (!source.IsCancellationRequested)
            {
                KeepAliveEvent result = await ProbeAsync(config, source.Token).ConfigureAwait(false);
                ProbeCompleted?.Invoke(this, result);
                TimeSpan delay = config.RandomDelay();
                await Task.Delay(delay, source.Token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (source.IsCancellationRequested)
        {
        }
        finally
        {
            active.TryRemove(new KeyValuePair<string, CancellationTokenSource>(config.Domain, source));
        }
    }

    private async Task<KeepAliveEvent> ProbeAsync(KeepAliveConfig config, CancellationToken cancellationToken)
    {
        long started = Environment.TickCount64;
        try
        {
            string detail;
            if (config.Method == KeepAliveMethod.PING)
            {
                string host = NormalizeHost(config.Domain);
                using Ping ping = new();
                PingReply reply = await ping.SendPingAsync(host, 8000)
                    .WaitAsync(cancellationToken).ConfigureAwait(false);
                detail = reply.Status == IPStatus.Success
                    ? $"Ping {reply.RoundtripTime} ms" : $"Ping {reply.Status}";
                if (reply.Status != IPStatus.Success) { throw new IOException(detail); }
            }
            else
            {
                Uri uri = NormalizeHttpUri(config.Domain);
                using HttpRequestMessage request = new(HttpMethod.Get, uri);
                using HttpResponseMessage response = await client.SendAsync(
                    request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
                detail = $"HTTP {(int)response.StatusCode}";
                if (!response.IsSuccessStatusCode) { throw new HttpRequestException(detail); }
            }
            return new(DateTimeOffset.Now, config.Domain, true,
                TimeSpan.FromMilliseconds(Environment.TickCount64 - started), detail);
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception exception) when (exception is IOException or PingException or HttpRequestException)
        {
            return new(DateTimeOffset.Now, config.Domain, false,
                TimeSpan.FromMilliseconds(Environment.TickCount64 - started), exception.Message);
        }
    }

    private static void Validate(IReadOnlyList<KeepAliveConfig> configs)
    {
        HashSet<string> domains = new(StringComparer.OrdinalIgnoreCase);
        foreach (KeepAliveConfig config in configs)
        {
            if (config is null || string.IsNullOrWhiteSpace(config.Domain)
                || config.MinInterval <= 0 || config.MaxInterval < config.MinInterval
                || config.MaxInterval > 1000 || !domains.Add(config.Domain))
            {
                throw new InvalidDataException("保活配置不符合当前格式。");
            }
            _ = NormalizeHttpUri(config.Domain);
        }
    }

    private static Uri NormalizeHttpUri(string value)
    {
        string normalized = value.Contains("://", StringComparison.Ordinal) ? value : $"https://{value}";
        if (!Uri.TryCreate(normalized, UriKind.Absolute, out Uri? uri)
            || uri.Scheme is not ("http" or "https") || string.IsNullOrWhiteSpace(uri.Host))
        {
            throw new InvalidDataException($"无效的域名或 URL: {value}");
        }
        return uri;
    }

    private static string NormalizeHost(string value) => NormalizeHttpUri(value).Host;

    public async ValueTask DisposeAsync()
    {
        foreach (string domain in active.Keys) { Stop(domain); }
        client.Dispose();
        store.Dispose();
        await Task.CompletedTask.ConfigureAwait(false);
    }
}

public sealed class KeepAliveConfig
{
    public string Domain { get; set; } = string.Empty;
    public bool Enabled { get; set; } = true;
    public KeepAliveMethod Method { get; set; } = KeepAliveMethod.HTTP;
    public int MinInterval { get; set; } = 5;
    public int MaxInterval { get; set; } = 10;
    public IntervalUnit Unit { get; set; } = IntervalUnit.MINUTES;

    public TimeSpan RandomDelay()
    {
        int value = Random.Shared.Next(MinInterval, checked(MaxInterval + 1));
        return Unit switch
        {
            IntervalUnit.MINUTES => TimeSpan.FromMinutes(value),
            IntervalUnit.HOURS => TimeSpan.FromHours(value),
            IntervalUnit.DAYS => TimeSpan.FromDays(value),
            _ => throw new InvalidOperationException("保活时间单位无效。")
        };
    }
}

public sealed record KeepAliveEvent(
    DateTimeOffset Timestamp,
    string Domain,
    bool Success,
    TimeSpan Elapsed,
    string Detail);

public enum KeepAliveMethod { HTTP, PING }
public enum IntervalUnit { MINUTES, HOURS, DAYS }
