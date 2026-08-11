package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.LogMatchMode;
import plugin.javafxtools.model.LogMonitorRule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogMonitorMatcherTest {

    @Test
    void matchesMultiCharacterContainsExpression() {
        LogMonitorRule rule = rule("rate-limit", "限流", "Too Many Requests",
                LogMatchMode.CONTAINS, false, true);

        LogMonitorMatcher matcher = new LogMonitorMatcher(List.of(rule));

        assertEquals(List.of(rule), matcher.matchingRules("HTTP: too many requests"));
    }

    @Test
    void containsMatchingHonorsCaseSensitivity() {
        LogMonitorRule insensitive = rule("insensitive", "不区分大小写", "Service Unavailable",
                LogMatchMode.CONTAINS, false, true);
        LogMonitorRule sensitive = rule("sensitive", "区分大小写", "Service Unavailable",
                LogMatchMode.CONTAINS, true, true);

        LogMonitorMatcher matcher = new LogMonitorMatcher(List.of(insensitive, sensitive));

        assertEquals(List.of(insensitive), matcher.matchingRules("service unavailable"));
    }

    @Test
    void wholeTokenUsesUnicodeLetterDigitAndUnderscoreBoundaries() {
        LogMonitorRule rule = rule("429", "请求过多", "429", LogMatchMode.WHOLE_TOKEN, true, true);
        LogMonitorMatcher matcher = new LogMonitorMatcher(List.of(rule));

        assertEquals(List.of(rule), matcher.matchingRules("状态：429，稍后重试"));
        assertEquals(List.of(), matcher.matchingRules("x429 429x _429 429_"));
        assertEquals(List.of(), matcher.matchingRules("请求429次"));
        assertEquals(List.of(), matcher.matchingRules("429\u0661"));
    }

    @Test
    void regexUsesFindInsteadOfWholeLineMatching() {
        LogMonitorRule rule = rule("server-error", "服务器错误", "5\\d{2}",
                LogMatchMode.REGEX, true, true);

        LogMonitorMatcher matcher = new LogMonitorMatcher(List.of(rule));

        assertEquals(List.of(rule), matcher.matchingRules("response status=503 unavailable"));
    }

    @Test
    void ignoresDisabledRules() {
        LogMonitorRule rule = rule("disabled", "已禁用", "429", LogMatchMode.WHOLE_TOKEN, true, false);

        LogMonitorMatcher matcher = new LogMonitorMatcher(List.of(rule));

        assertEquals(List.of(), matcher.matchingRules("HTTP 429"));
    }

    @Test
    void returnsEachRuleOncePerLine() {
        LogMonitorRule rule = rule("rate-limit", "限流", "429", LogMatchMode.WHOLE_TOKEN, true, true);

        LogMonitorMatcher matcher = new LogMonitorMatcher(List.of(rule));

        assertEquals(List.of(rule), matcher.matchingRules("429 then 429 again"));
    }

    @Test
    void rejectsBlankExpressions() {
        LogMonitorRule rule = rule("blank", "空", "  ", LogMatchMode.CONTAINS, true, true);

        assertThrows(IllegalArgumentException.class, () -> new LogMonitorMatcher(List.of(rule)));
    }

    @Test
    void rejectsInvalidRegularExpressions() {
        LogMonitorRule rule = rule("invalid", "无效", "[", LogMatchMode.REGEX, true, true);

        assertThrows(IllegalArgumentException.class, () -> new LogMonitorMatcher(List.of(rule)));
    }

    @Test
    void rejectsInvalidRegularExpressionsForDisabledRules() {
        LogMonitorRule rule = rule("disabled-invalid", "已禁用无效", "[", LogMatchMode.REGEX, true, false);

        assertThrows(IllegalArgumentException.class, () -> new LogMonitorMatcher(List.of(rule)));
    }

    @Test
    void rejectsDuplicateModeExpressionAndCaseSensitivityDefinitions() {
        LogMonitorRule first = rule("first", "第一条", "429", LogMatchMode.WHOLE_TOKEN, true, true);
        LogMonitorRule duplicate = rule("second", "第二条", "429", LogMatchMode.WHOLE_TOKEN, true, false);

        assertThrows(IllegalArgumentException.class,
                () -> new LogMonitorMatcher(List.of(first, duplicate)));
    }

    private LogMonitorRule rule(String id, String name, String expression, LogMatchMode mode,
                                boolean caseSensitive, boolean enabled) {
        return new LogMonitorRule(id, name, expression, mode, caseSensitive, enabled);
    }
}
