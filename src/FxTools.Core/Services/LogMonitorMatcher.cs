using System.Text.RegularExpressions;

namespace FxTools.Core.Services;

public sealed class LogMonitorMatcher
{
    private readonly CompiledRule[] rules;

    public LogMonitorMatcher(IReadOnlyList<LogMonitorRule> rules)
    {
        ArgumentNullException.ThrowIfNull(rules);
        HashSet<string> definitions = new(StringComparer.Ordinal);
        List<CompiledRule> compiled = [];
        foreach (LogMonitorRule rule in rules)
        {
            Validate(rule);
            string key = $"{rule.Mode}\0{rule.CaseSensitive}\0{rule.Expression}";
            if (!definitions.Add(key)) { throw new ArgumentException($"重复的触发规则: {rule.Expression}", nameof(rules)); }
            if (rule.Enabled) { compiled.Add(Compile(rule)); }
        }
        this.rules = compiled.ToArray();
    }

    public IReadOnlyList<LogMonitorRule> Match(string line)
    {
        ArgumentNullException.ThrowIfNull(line);
        return rules.Where(rule => rule.IsMatch(line)).Select(rule => rule.Rule).ToArray();
    }

    private static void Validate(LogMonitorRule rule)
    {
        ArgumentNullException.ThrowIfNull(rule);
        if (string.IsNullOrWhiteSpace(rule.Id) || string.IsNullOrWhiteSpace(rule.Name)
            || string.IsNullOrWhiteSpace(rule.Expression))
        {
            throw new ArgumentException("规则 ID、名称和表达式不能为空。");
        }
        if (rule.Mode == LogMatchMode.Regex) { _ = CreateRegex(rule); }
    }

    private static CompiledRule Compile(LogMonitorRule rule) => rule.Mode switch
    {
        LogMatchMode.Regex => new(rule, CreateRegex(rule), null),
        LogMatchMode.WholeToken => new(rule, new Regex(
            $"(?<![\\p{{L}}\\p{{N}}_]){Regex.Escape(rule.Expression)}(?![\\p{{L}}\\p{{N}}_])",
            RegexOptions.CultureInvariant | (rule.CaseSensitive ? RegexOptions.None : RegexOptions.IgnoreCase),
            TimeSpan.FromMilliseconds(100)), null),
        _ => new(rule, null, rule.Expression)
    };

    private static Regex CreateRegex(LogMonitorRule rule)
    {
        try
        {
            return new(rule.Expression,
                RegexOptions.CultureInvariant | (rule.CaseSensitive ? RegexOptions.None : RegexOptions.IgnoreCase),
                TimeSpan.FromMilliseconds(100));
        }
        catch (ArgumentException exception)
        {
            throw new ArgumentException($"无效的正则表达式: {rule.Expression}", nameof(rule), exception);
        }
    }

    private sealed record CompiledRule(LogMonitorRule Rule, Regex? Regex, string? Literal)
    {
        public bool IsMatch(string line) => Regex?.IsMatch(line)
            ?? line.Contains(Literal!, Rule.CaseSensitive
                ? StringComparison.Ordinal : StringComparison.OrdinalIgnoreCase);
    }
}
