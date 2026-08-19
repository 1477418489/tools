using System.Text.Json.Serialization;

namespace FxTools.Core.Services;

public sealed class LogMonitorConfig
{
    public bool Enabled { get; set; } = true;
    public string LogFile { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "cc-switch.log");
    public List<LogMonitorRule> Rules { get; set; } = DefaultRules();
    public LogMonitorAutomation Automation { get; set; } = LogMonitorAutomation.Default();

    public static LogMonitorConfig Default() => new();

    private static List<LogMonitorRule> DefaultRules() =>
    [
        new() { Id = "429", Name = "HTTP 429", Expression = "429", Mode = LogMatchMode.WholeToken },
        new() { Id = "503", Name = "HTTP 503", Expression = "503", Mode = LogMatchMode.WholeToken }
    ];
}

public sealed class LogMonitorRule
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = string.Empty;
    public string Expression { get; set; } = string.Empty;
    public LogMatchMode Mode { get; set; } = LogMatchMode.Contains;
    public bool CaseSensitive { get; set; } = true;
    public bool Enabled { get; set; } = true;
}

public sealed class LogMonitorAutomation
{
    public bool Enabled { get; set; }
    public List<string> TriggerRuleIds { get; set; } = ["429"];
    public string TargetWindow { get; set; } = string.Empty;
    public bool TypeText { get; set; } = true;
    public string Text { get; set; } = "继续";
    public bool PressEnter { get; set; } = true;
    public int StartAtMatch { get; set; } = 1;
    public int EveryMatches { get; set; } = 1;
    public int MaxExecutions { get; set; } = 1;
    public bool RemoteCheckEnabled { get; set; }
    public string RemoteUrl { get; set; } = "https://chybenzun.top";
    public string RemoteKeyword { get; set; } = "continue";
    public LogRemoteMatchAction RemoteMatchAction { get; set; } = LogRemoteMatchAction.ContinueInput;

    public static LogMonitorAutomation Default() => new();
}

public sealed record LogMonitorMatch(
    DateTimeOffset Timestamp,
    string RuleId,
    string RuleName,
    string Line);

public enum LogMatchMode
{
    [JsonStringEnumMemberName("CONTAINS")] Contains,
    [JsonStringEnumMemberName("WHOLE_TOKEN")] WholeToken,
    [JsonStringEnumMemberName("REGEX")] Regex
}
public enum LogRemoteMatchAction
{
    [JsonStringEnumMemberName("CONTINUE_INPUT")] ContinueInput,
    [JsonStringEnumMemberName("NO_ACTION")] NoAction
}
