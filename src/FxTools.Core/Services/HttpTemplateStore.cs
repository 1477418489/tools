using FxTools.Core.Infrastructure;

namespace FxTools.Core.Services;

public sealed class HttpTemplateStore : IDisposable
{
    private static readonly HashSet<string> SupportedMethods =
        new(["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"], StringComparer.OrdinalIgnoreCase);
    private readonly AtomicJsonStore store;
    private readonly string path;

    public HttpTemplateStore(string? path = null)
    {
        this.path = path ?? AppDataPaths.DataFile("http_templates.json");
        store = new(this.path);
    }

    public async Task<Dictionary<string, HttpTemplate>> LoadAsync(CancellationToken cancellationToken = default)
    {
        Dictionary<string, HttpTemplate> result = await store.LoadAsync(
            () => new Dictionary<string, HttpTemplate>(StringComparer.OrdinalIgnoreCase),
            cancellationToken).ConfigureAwait(false);
        Validate(result);
        return new(result, StringComparer.OrdinalIgnoreCase);
    }

    public async Task SaveAsync(
        IReadOnlyDictionary<string, HttpTemplate> templates,
        CancellationToken cancellationToken = default)
    {
        Validate(templates);
        if (File.Exists(path))
        {
            _ = await LoadAsync(cancellationToken).ConfigureAwait(false);
        }
        await store.SaveAsync(templates, cancellationToken).ConfigureAwait(false);
    }

    private static void Validate(IReadOnlyDictionary<string, HttpTemplate> templates)
    {
        foreach ((string name, HttpTemplate template) in templates)
        {
            if (string.IsNullOrWhiteSpace(name) || name.Length > 100 || template is null
                || !Uri.TryCreate(template.Url, UriKind.Absolute, out Uri? uri)
                || uri.Scheme is not ("http" or "https")
                || !SupportedMethods.Contains(template.Method ?? string.Empty))
            {
                throw new InvalidDataException("HTTP 模板不符合当前格式。");
            }
        }
    }

    public void Dispose() => store.Dispose();
}

public sealed class HttpTemplate
{
    public string Url { get; set; } = string.Empty;
    public string Method { get; set; } = "GET";
    public string Params { get; set; } = string.Empty;
    public string Headers { get; set; } = string.Empty;
    public string Interval { get; set; } = "60";
    public string ConnectTimeout { get; set; } = "10";
    public string ReadTimeout { get; set; } = "30";
}
