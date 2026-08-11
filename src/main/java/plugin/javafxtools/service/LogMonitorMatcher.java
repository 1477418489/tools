package plugin.javafxtools.service;

import plugin.javafxtools.model.LogMatchMode;
import plugin.javafxtools.model.LogMonitorRule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class LogMonitorMatcher {
    private static final String TOKEN_CHARACTER_CLASS = "[\\p{L}\\p{N}_]";

    private final List<CompiledRule> enabledRules;

    public LogMonitorMatcher(List<LogMonitorRule> rules) {
        Objects.requireNonNull(rules, "rules");
        Set<RuleDefinition> definitions = new HashSet<>();
        List<CompiledRule> compiledRules = new ArrayList<>();
        for (LogMonitorRule rule : rules) {
            Pattern regexPattern = validate(rule, definitions);
            if (rule.enabled()) {
                compiledRules.add(compile(rule, regexPattern));
            }
        }
        enabledRules = List.copyOf(compiledRules);
    }

    public List<LogMonitorRule> matchingRules(String line) {
        Objects.requireNonNull(line, "line");
        List<LogMonitorRule> matches = new ArrayList<>();
        for (CompiledRule compiledRule : enabledRules) {
            if (compiledRule.matches(line)) {
                matches.add(compiledRule.rule());
            }
        }
        return List.copyOf(matches);
    }

    private static Pattern validate(LogMonitorRule rule, Set<RuleDefinition> definitions) {
        Objects.requireNonNull(rule, "rule");
        if (rule.expression() == null || rule.expression().isBlank()) {
            throw new IllegalArgumentException("Rule expression must not be blank");
        }
        if (rule.mode() == null) {
            throw new IllegalArgumentException("Rule match mode must not be null");
        }
        RuleDefinition definition = new RuleDefinition(rule.mode(), rule.expression(), rule.caseSensitive());
        if (!definitions.add(definition)) {
            throw new IllegalArgumentException("Duplicate rule definition: " + rule.expression());
        }
        return rule.mode() == LogMatchMode.REGEX ? compileRegex(rule) : null;
    }

    private static CompiledRule compile(LogMonitorRule rule, Pattern regexPattern) {
        String expression = rule.caseSensitive() ? rule.expression() : rule.expression().toLowerCase(Locale.ROOT);
        return switch (rule.mode()) {
            case CONTAINS -> new CompiledRule(rule, null, expression);
            case WHOLE_TOKEN -> new CompiledRule(rule,
                    Pattern.compile("(?<!" + TOKEN_CHARACTER_CLASS + ")" + Pattern.quote(expression)
                            + "(?!" + TOKEN_CHARACTER_CLASS + ")"), expression);
            case REGEX -> new CompiledRule(rule, regexPattern, null);
        };
    }

    private static Pattern compileRegex(LogMonitorRule rule) {
        try {
            int flags = rule.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            return Pattern.compile(rule.expression(), flags);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("Invalid regular expression: " + rule.expression(), exception);
        }
    }

    private record RuleDefinition(LogMatchMode mode, String expression, boolean caseSensitive) {
    }

    private record CompiledRule(LogMonitorRule rule, Pattern pattern, String literalExpression) {
        private boolean matches(String line) {
            if (pattern != null) {
                String candidate = rule.caseSensitive() || rule.mode() == LogMatchMode.REGEX
                        ? line : line.toLowerCase(Locale.ROOT);
                return pattern.matcher(candidate).find();
            }
            String candidate = rule.caseSensitive() ? line : line.toLowerCase(Locale.ROOT);
            return candidate.contains(literalExpression);
        }
    }
}
