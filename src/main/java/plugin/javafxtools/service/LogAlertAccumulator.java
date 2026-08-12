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

    public record RuleSummary(String ruleId, String ruleName, String expression, int count,
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

        public int totalCount() {
            return rules.stream().mapToInt(RuleSummary::count).sum();
        }
    }

    public record Change(Action action, Snapshot snapshot, Optional<Instant> nextWakeUp) {
        public Change {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(nextWakeUp, "nextWakeUp");
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
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(now, "now");

        Bucket activeBucket = active.get(match.ruleId());
        if (activeBucket != null) {
            activeBucket.add(match);
            return change(Action.UPDATE);
        }

        Instant ruleBlockedUntil = blockedUntil.get(match.ruleId());
        if (ruleBlockedUntil != null && now.isBefore(ruleBlockedUntil)) {
            pending.computeIfAbsent(match.ruleId(), ignored -> new Bucket(match)).add(match);
            return change(Action.NONE);
        }

        blockedUntil.remove(match.ruleId());
        boolean dialogWasOpen = !active.isEmpty();
        Bucket bucket = active.computeIfAbsent(match.ruleId(), ignored -> new Bucket(match));
        mergePending(match.ruleId(), bucket);
        bucket.add(match);
        return change(dialogWasOpen ? Action.UPDATE : Action.SHOW);
    }

    public Change onMatches(List<LogMonitorMatch> matches, Instant now) {
        Objects.requireNonNull(matches, "matches");
        Objects.requireNonNull(now, "now");
        Action aggregateAction = Action.NONE;
        for (LogMonitorMatch match : matches) {
            Action action = onMatch(Objects.requireNonNull(match, "match"), now).action();
            if (action == Action.SHOW) {
                aggregateAction = Action.SHOW;
            } else if (action == Action.UPDATE && aggregateAction == Action.NONE) {
                aggregateAction = Action.UPDATE;
            }
        }
        return change(aggregateAction);
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
        private int count;
        private Instant firstMatchedAt;
        private Instant lastMatchedAt;
        private final ArrayDeque<String> recentLines = new ArrayDeque<>();

        private Bucket(LogMonitorMatch match) {
            ruleId = Objects.requireNonNull(match.ruleId(), "match.ruleId");
            ruleName = Objects.requireNonNull(match.ruleName(), "match.ruleName");
            expression = Objects.requireNonNull(match.expression(), "match.expression");
        }

        private void add(LogMonitorMatch match) {
            ruleName = Objects.requireNonNull(match.ruleName(), "match.ruleName");
            expression = Objects.requireNonNull(match.expression(), "match.expression");
            Instant matchedAt = Objects.requireNonNull(match.matchedAt(), "match.matchedAt");
            String line = Objects.requireNonNull(match.line(), "match.line");
            if (count == 0) {
                firstMatchedAt = matchedAt;
            }
            count++;
            lastMatchedAt = matchedAt;
            recentLines.addLast(line);
            while (recentLines.size() > retainedLinesPerRule) {
                recentLines.removeFirst();
            }
        }

        private void merge(Bucket other) {
            if (other == this || other.count == 0) {
                return;
            }
            if (count == 0) {
                firstMatchedAt = other.firstMatchedAt;
            }
            count += other.count;
            lastMatchedAt = other.lastMatchedAt;
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
}
