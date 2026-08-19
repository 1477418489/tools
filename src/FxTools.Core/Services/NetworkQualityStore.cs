using FxTools.Core.Infrastructure;

namespace FxTools.Core.Services;

public sealed class NetworkQualityStore : IDisposable
{
    private readonly AtomicJsonStore targetStore = new(AppDataPaths.DataFile("network_quality_targets.json"));
    private readonly AtomicJsonStore settingsStore = new(AppDataPaths.DataFile("network_quality_settings.json"));

    public Task<List<NetworkTarget>> LoadTargetsAsync(CancellationToken cancellationToken = default) =>
        targetStore.LoadAsync(() => DefaultTargets(), cancellationToken);

    public Task SaveTargetsAsync(IReadOnlyList<NetworkTarget> targets, CancellationToken cancellationToken = default)
    {
        if (targets.Count > 100 || targets.Any(target => string.IsNullOrWhiteSpace(target.Name)
                                                        || string.IsNullOrWhiteSpace(target.Host)
                                                        || target.Port is < 1 or > 65535))
        {
            throw new InvalidDataException("网络质量目标配置无效。");
        }
        return targetStore.SaveAsync(targets, cancellationToken);
    }

    public Task<NetworkQualitySettings> LoadSettingsAsync(CancellationToken cancellationToken = default) =>
        settingsStore.LoadAsync(() => new NetworkQualitySettings(), cancellationToken);

    public Task SaveSettingsAsync(NetworkQualitySettings settings, CancellationToken cancellationToken = default) =>
        settingsStore.SaveAsync(settings, cancellationToken);

    private static List<NetworkTarget> DefaultTargets() =>
    [
        new("Cloudflare HTTPS", NetworkProbeProtocol.Http, "1.1.1.1", 443, "/"),
        new("Google TLS", NetworkProbeProtocol.Tls, "www.google.com", 443),
        new("Cloudflare STUN", NetworkProbeProtocol.Stun, "stun.cloudflare.com", 3478)
    ];

    public void Dispose()
    {
        targetStore.Dispose();
        settingsStore.Dispose();
    }
}

public sealed class NetworkQualitySettings
{
    public double IntervalSeconds { get; set; } = 5;
    public double TimeoutSeconds { get; set; } = 5;
    public bool UseProxy { get; set; }
    public string ProxyUrl { get; set; } = string.Empty;
}
