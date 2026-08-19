package plugin.javafxtools.service;

import plugin.javafxtools.model.LogMonitorMatch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pure state machine for dialog aggregation and per-rule cooldowns. */
public final class LogAlertAccumulator {
    public enum Action {
        NONE,
        SHOW,
        UPDATE
    }

    public record RuleSummary(String ruleId, String ruleName, String expression, long count,
                              Instant firstMatchedAt, Instant lastMatchedAt,
                              List<String> recentLines) {
        public RuleSummary {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(ruleName, "ruleName");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(firstMatchedAt, "firstMatchedAt");
            Objects.requireNonNull(lastMatchedAt, "lastMatchedAt");
            recentLines = List.copyOf(Objects.requireNonNull(recentLines, "recentLines"));
        }
    }

    public record Snapshot(List<RuleSummary> rules) {
        public Snapshot {
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        }

        public long totalCount() {
            long total = 0;
            for (RuleSummary rule : rules) {
                total = saturatedAdd(total, rule.count());
            }
            return total;
        }
    }

    public record Change(Action action, Snapshot snapshot, Optional<Instant> nextWakeUp) {
        public Change {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(nextWakeUp, "nextWakeUp");
        }
    }

    record MatchSummary(String ruleId, String ruleName, String expression, long count,
                        Instant firstMatchedAt, Instant lastMatchedAt,
                        List<String> recentLines) {
        MatchSummary {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(ruleName, "ruleName");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(firstMatchedAt, "firstMatchedAt");
            Objects.requireNonNull(lastMatchedAt, "lastMatchedAt");
            recentLines = List.copyOf(Objects.requireNonNull(recentLines, "recentLines"));
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
            if (lastMatchedAt.isBefore(firstMatchedAt)) {
                throw new IllegalArgumentException("lastMatchedAt must not precede firstMatchedAt");
            }
        }

        private static MatchSummary from(LogMonitorMatch match) {
            Objects.requireNonNull(match, "match");
            Instant matchedAt = Objects.requireNonNull(match.matchedAt(), "match.matchedAt");
            return new MatchSummary(
                    Objects.requireNonNull(match.ruleId(), "match.ruleId"),
                    Objects.requireNonNull(match.ruleName(), "match.ruleName"),
                    Objects.requireNonNull(match.expression(), "match.expression"),
                    1L, matchedAt, matchedAt,
                    List.of(Objects.requireNonNull(match.line(), "match.line")));
        }
    }

    private final Duration cooldown;
    private final int retainedLinesPerRule;
    private final LinkedHashMap<String, Bucket> active = new LinkedHashMap<>();
    private final LinkedHashMap<String, Bucket> pending = new LinkedHashMap<>();
    private final Map<String, Instant> blockedUntil = new HashMap<>();

    public LogAlertAccumulator(Duration cooldown, int retainedLinesPerRule) {
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        if (cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must be positive");
        }
        if (retainedLinesPerRule <= 0) {
            throw new IllegalArgumentException("retainedLinesPerRule must be positive");
        }
        this.retainedLinesPerRule = retainedLinesPerRule;
    }

    public Change onMatch(LogMonitorMatch match, Instant now) {
        return onSummaries(List.of(MatchSummary.from(match)), now);
    }

    public Change onMatches(List<LogMonitorMatch> matches, Instant now) {
        Objects.requireNonNull(matches, "matches");
        Objects.requireNonNull(now, "now");
        Action aggregateAction = Action.NONE;
        for (LogMonitorMatch match : matches) {
            aggregateAction = combine(aggregateAction,
                    applySummary(MatchSummary.from(match), now));
        }
        return change(aggregateAction);
    }

    Change onSummaries(List<MatchSummary> summaries, Instant now) {
        Objects.requireNonNull(summaries, "summaries");
        Objects.requireNonNull(now, "now");
        Action aggregateAction = Action.NONE;
        for (MatchSummary summary : summaries) {
            aggregateAction = combine(aggregateAction,
                    applySummary(Objects.requireNonNull(summary, "summary"), now));
        }
        return change(aggregateAction);
    }

    public void reset() {
        active.clear();
        pending.clear();
        blockedUntil.clear();
    }

    private Action applySummary(MatchSummary summary, Instant now) {
        Bucket activeBucket = active.get(summary.ruleId());
        if (activeBucket != null) {
            activeBucket.add(summary);
            return Action.UPDATE;
        }

        Instant ruleBlockedUntil = blockedUntil.get(summary.ruleId());
        if (ruleBlockedUntil != null && now.isBefore(ruleBlockedUntil)) {
            pending.computeIfAbsent(summary.ruleId(), ignored -> new Bucket(summary)).add(summary);
            return Action.NONE;
        }

        blockedUntil.remove(summary.ruleId());
        boolean dialogWasOpen = !active.isEmpty();
        Bucket bucket = active.computeIfAbsent(summary.ruleId(), ignored -> new Bucket(summary));
        mergePending(summary.ruleId(), bucket);
        bucket.add(summary);
        return dialogWasOpen ? Action.UPDATE : Action.SHOW;
    }

    private static Action combine(Action aggregate, Action next) {
        if (aggregate == Action.SHOW || next == Action.SHOW) {
            return Action.SHOW;
        }
        return aggregate == Action.UPDATE || next == Action.UPDATE ? Action.UPDATE : Action.NONE;
    }

    public Change onDialogClosed(Instant now) {
        Objects.requireNonNull(now, "now");
        for (String ruleId : active.keySet()) {
            blockedUntil.put(ruleId, now.plus(cooldown));
        }
        active.clear();
        return change(Action.NONE);
    }

    public Change cooldownElapsed(Instant now) {
        Objects.requireNonNull(now, "now");
        boolean dialogWasOpen = !active.isEmpty();
        boolean promoted = false;
        Iterator<Map.Entry<String, Bucket>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Bucket> entry = iterator.next();
            Instant ruleBlockedUntil = blockedUntil.get(entry.getKey());
            if (ruleBlockedUntil == null || !now.isBefore(ruleBlockedUntil)) {
                active.computeIfAbsent(entry.getKey(), ignored -> entry.getValue())
                        .merge(entry.getValue());
                blockedUntil.remove(entry.getKey());
                iterator.remove();
                promoted = true;
            }
        }
        if (!promoted) {
            return change(Action.NONE);
        }
        return change(dialogWasOpen ? Action.UPDATE : Action.SHOW);
    }

    private void mergePending(String ruleId, Bucket target) {
        Bucket waiting = pending.remove(ruleId);
        if (waiting != null) {
            target.merge(waiting);
        }
    }

    private Change change(Action action) {
        List<RuleSummary> summaries = active.values().stream().map(Bucket::snapshot).toList();
        Optional<Instant> nextWakeUp = pending.keySet().stream()
                .map(blockedUntil::get)
                .filter(Objects::nonNull)
                .min(Instant::compareTo);
        return new Change(action, new Snapshot(summaries), nextWakeUp);
    }

    private final class Bucket {
        private final String ruleId;
        private String ruleName;
        private String expression;
        private long count;
        private Instant firstMatchedAt;
        private Instant lastMatchedAt;
        private final ArrayDeque<String> recentLines = new ArrayDeque<>();

        private Bucket(MatchSummary summary) {
            ruleId = summary.ruleId();
            ruleName = summary.ruleName();
            expression = summary.expression();
        }

        private void add(MatchSummary summary) {
            ruleName = summary.ruleName();
            expression = summary.expression();
            if (count == 0) {
                firstMatchedAt = summary.firstMatchedAt();
            } else if (summary.firstMatchedAt().isBefore(firstMatchedAt)) {
                firstMatchedAt = summary.firstMatchedAt();
            }
            count = saturatedAdd(count, summary.count());
            if (lastMatchedAt == null || summary.lastMatchedAt().isAfter(lastMatchedAt)) {
                lastMatchedAt = summary.lastMatchedAt();
            }
            for (String line : summary.recentLines()) {
                recentLines.addLast(line);
                while (recentLines.size() > retainedLinesPerRule) {
                    recentLines.removeFirst();
                }
            }
        }

        private void merge(Bucket other) {
            if (other == this || other.count == 0) {
                return;
            }
            if (count == 0) {
                firstMatchedAt = other.firstMatchedAt;
            } else if (other.firstMatchedAt.isBefore(firstMatchedAt)) {
                firstMatchedAt = other.firstMatchedAt;
            }
            count = saturatedAdd(count, other.count);
            if (lastMatchedAt == null || other.lastMatchedAt.isAfter(lastMatchedAt)) {
                lastMatchedAt = other.lastMatchedAt;
            }
            ruleName = other.ruleName;
            expression = other.expression;
            for (String line : other.recentLines) {
                recentLines.addLast(line);
                if (recentLines.size() > retainedLinesPerRule) {
                    recentLines.removeFirst();
                }
            }
        }

        private RuleSummary snapshot() {
            return new RuleSummary(ruleId, ruleName, expression, count,
                    firstMatchedAt, lastMatchedAt, new ArrayList<>(recentLines));
        }
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
