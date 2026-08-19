using System.Text.Json;
using FxTools.Core.Infrastructure;

namespace FxTools.Core.Services;

public sealed class LogMonitorStore : IDisposable
{
    private readonly string path;
    private readonly AtomicJsonStore store;

    public LogMonitorStore(string? path = null)
    {
        this.path = path ?? AppDataPaths.DataFile("log-monitor.json");
        store = new(this.path);
    }

    public async Task<LogMonitorConfig> LoadAsync(CancellationToken cancellationToken = default)
    {
        LogMonitorConfig config = await store.LoadAsync(LogMonitorConfig.Default, cancellationToken).ConfigureAwait(false);
        if (File.Exists(path))
        {
            await using FileStream stream = File.OpenRead(path);
            using JsonDocument document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken)
                .ConfigureAwait(false);
            if (TryProperty(document.RootElement, "automation", out JsonElement automation)
                && TryProperty(automation, "triggerRuleId", out JsonElement legacy)
                && legacy.ValueKind == JsonValueKind.String)
            {
                config.Automation.TriggerRuleIds = [legacy.GetString() ?? string.Empty];
            }
        }
        Validate(config);
        return config;
    }

    public async Task SaveAsync(LogMonitorConfig config, CancellationToken cancellationToken = default)
    {
        Validate(config);
        if (File.Exists(path)) { _ = await LoadAsync(cancellationToken).ConfigureAwait(false); }
        await store.SaveAsync(config, cancellationToken).ConfigureAwait(false);
    }

    public static void Validate(LogMonitorConfig config)
    {
        ArgumentNullException.ThrowIfNull(config);
        if (string.IsNullOrWhiteSpace(config.LogFile)) { throw new InvalidDataException("日志文件不能为空。"); }
        _ = Path.GetFullPath(config.LogFile);
        _ = new LogMonitorMatcher(config.Rules);
        HashSet<string> ids = config.Rules.Select(rule => rule.Id).ToHashSet(StringComparer.Ordinal);
        if (ids.Count != config.Rules.Count) { throw new InvalidDataException("触发规则 ID 不能重复。"); }
        LogMonitorAutomation automation = config.Automation ?? throw new InvalidDataException("自动响应配置不能为空。");
        if (!automation.Enabled) { return; }
        if (automation.TriggerRuleIds.Count == 0
            || automation.TriggerRuleIds.Distinct(StringComparer.Ordinal).Count() != automation.TriggerRuleIds.Count
            || automation.TriggerRuleIds.Any(id => !config.Rules.Any(rule => rule.Enabled && rule.Id == id)))
        {
            throw new InvalidDataException("自动响应至少选择一条已启用且不重复的触发规则。");
        }
        if (string.IsNullOrWhiteSpace(automation.TargetWindow))
        {
            throw new InvalidDataException("请选择自动输入目标窗口。");
        }
        _ = Windows.WindowsWindowService.ParseSelector(automation.TargetWindow);
        if (!automation.TypeText && !automation.PressEnter) { throw new InvalidDataException("至少启用文本输入或回车。"); }
        if (automation.TypeText && string.IsNullOrEmpty(automation.Text)) { throw new InvalidDataException("自动输入文本不能为空。"); }
        if (automation.Text.Length > 4096) { throw new InvalidDataException("自动输入文本不能超过 4096 个字符。"); }
        if (automation.StartAtMatch < 1 || automation.EveryMatches < 1 || automation.MaxExecutions < 0
            || automation.StartAtMatch > 1_000_000 || automation.EveryMatches > 1_000_000
            || automation.MaxExecutions > 1_000_000)
        {
            throw new InvalidDataException("自动响应次数配置无效。");
        }
        if (automation.RemoteCheckEnabled
            && (!Uri.TryCreate(automation.RemoteUrl, UriKind.Absolute, out Uri? remote)
                || remote.Scheme is not ("http" or "https")
                || string.IsNullOrEmpty(automation.RemoteKeyword)))
        {
            throw new InvalidDataException("远程判断 URL 或关键字无效。");
        }
    }

    private static bool TryProperty(JsonElement element, string name, out JsonElement value)
    {
        foreach (JsonProperty property in element.EnumerateObject())
        {
            if (string.Equals(property.Name, name, StringComparison.OrdinalIgnoreCase))
            {
                value = property.Value;
                return true;
            }
        }
        value = default;
        return false;
    }

    public void Dispose() => store.Dispose();
}
