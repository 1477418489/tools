package plugin.javafxtools.model;

import java.util.List;
import java.util.Objects;

/** Optional automated input performed after a configured log rule matches. */
public record LogMonitorAutomation(
        boolean enabled,
        List<String> triggerRuleIds,
        String targetWindow,
        boolean typeText,
        String text,
        boolean pressEnter,
        int startAtMatch,
        int everyMatches,
        int maxExecutions,
        boolean remoteCheckEnabled,
        String remoteUrl,
        String remoteKeyword,
        LogRemoteMatchAction remoteMatchAction) {
    // A Windows top-level window title can contain up to 32,767 characters.
    private static final int MAX_TARGET_CHARACTERS = 32_800;
    private static final int MAX_INPUT_CHARACTERS = 4_096;
    private static final int MAX_URL_CHARACTERS = 2_048;
    private static final int MAX_KEYWORD_CHARACTERS = 512;
    private static final int MAX_COUNT_VALUE = 1_000_000;

    public LogMonitorAutomation {
        triggerRuleIds = List.copyOf(Objects.requireNonNull(triggerRuleIds, "triggerRuleIds"));
        targetWindow = Objects.requireNonNull(targetWindow, "targetWindow");
        text = Objects.requireNonNull(text, "text");
        remoteUrl = Objects.requireNonNull(remoteUrl, "remoteUrl");
        remoteKeyword = Objects.requireNonNull(remoteKeyword, "remoteKeyword");
        remoteMatchAction = Objects.requireNonNull(remoteMatchAction, "remoteMatchAction");
        if (startAtMatch < 1) {
            throw new IllegalArgumentException("startAtMatch must be at least 1");
        }
        if (everyMatches < 1) {
            throw new IllegalArgumentException("everyMatches must be at least 1");
        }
        if (maxExecutions < 0) {
            throw new IllegalArgumentException("maxExecutions must not be negative");
        }
        if (startAtMatch > MAX_COUNT_VALUE || everyMatches > MAX_COUNT_VALUE
                || maxExecutions > MAX_COUNT_VALUE) {
            throw new IllegalArgumentException("自动响应次数不能超过 " + MAX_COUNT_VALUE);
        }
        if (triggerRuleIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("触发规则标识不能为空");
        }
        if (triggerRuleIds.size() == 1 && triggerRuleIds.getFirst().isBlank()) {
            triggerRuleIds = List.of();
        } else if (triggerRuleIds.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("触发规则标识不能为空");
        }
        if (triggerRuleIds.stream().distinct().count() != triggerRuleIds.size()) {
            throw new IllegalArgumentException("触发规则不能重复选择");
        }
        requireLength(targetWindow, MAX_TARGET_CHARACTERS, "目标 PID、窗口或进程名");
        requireLength(text, MAX_INPUT_CHARACTERS, "自定义输入文本");
        requireLength(remoteUrl, MAX_URL_CHARACTERS, "远程判断网址");
        requireLength(remoteKeyword, MAX_KEYWORD_CHARACTERS, "远程响应关键字");
    }

    public static LogMonitorAutomation defaults(List<LogMonitorRule> rules) {
        Objects.requireNonNull(rules, "rules");
        List<String> triggerRuleIds = rules.stream()
                .filter(LogMonitorRule::enabled)
                .map(LogMonitorRule::id)
                .findFirst()
                .map(List::of)
                .orElseGet(List::of);
        return new LogMonitorAutomation(false, triggerRuleIds, "Codex",
                true, "继续", true,
                1, 1, 1,
                false, "https://chybenzun.top", "continue",
                LogRemoteMatchAction.CONTINUE_INPUT);
    }

    public void validate(List<LogMonitorRule> rules) {
        Objects.requireNonNull(rules, "rules");
        if (!enabled) {
            return;
        }
        if (triggerRuleIds.isEmpty()) {
            throw new IllegalArgumentException("自动响应至少选择一条已启用的触发规则");
        }
        boolean allRulesExist = triggerRuleIds.stream().allMatch(triggerId -> rules.stream()
                .anyMatch(rule -> rule.enabled() && rule.id().equals(triggerId)));
        if (!allRulesExist) {
            throw new IllegalArgumentException("自动响应只能选择已启用的触发规则");
        }
        if (targetWindow.isBlank()) {
            throw new IllegalArgumentException("目标 PID、窗口或进程名不能为空");
        }
        if (!typeText && !pressEnter) {
            throw new IllegalArgumentException("自动响应至少需要输入文本或按下回车");
        }
        if (typeText && text.isEmpty()) {
            throw new IllegalArgumentException("启用文本输入时，自定义文本不能为空");
        }
        if (remoteCheckEnabled && remoteUrl.isBlank()) {
            throw new IllegalArgumentException("远程判断网址不能为空");
        }
        if (remoteCheckEnabled && remoteKeyword.isEmpty()) {
            throw new IllegalArgumentException("远程响应关键字不能为空");
        }
    }

    /** Compatibility accessor for callers that used one trigger rule. */
    public String triggerRuleId() {
        return triggerRuleIds.isEmpty() ? "" : triggerRuleIds.getFirst();
    }

    /** Compatibility constructor for the previous single-trigger configuration. */
    public LogMonitorAutomation(boolean enabled, String triggerRuleId, String targetWindow,
                                boolean typeText, String text, boolean pressEnter,
                                int startAtMatch, int everyMatches, int maxExecutions,
                                boolean remoteCheckEnabled, String remoteUrl,
                                String remoteKeyword, LogRemoteMatchAction remoteMatchAction) {
        this(enabled, List.of(Objects.requireNonNull(triggerRuleId, "triggerRuleId")), targetWindow,
                typeText, text, pressEnter, startAtMatch, everyMatches, maxExecutions,
                remoteCheckEnabled, remoteUrl, remoteKeyword, remoteMatchAction);
    }

    private static void requireLength(String value, int maximum, String fieldName) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maximum + " 个字符");
        }
    }
}
