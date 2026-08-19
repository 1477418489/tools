package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.stage.Modality;
import javafx.stage.Stage;
import plugin.javafxtools.model.LogMonitorMatch;
import plugin.javafxtools.util.FxTheme;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Adapts log matches to one aggregated, non-blocking JavaFX alert dialog. */
public final class LogMonitorAlertService implements AutoCloseable {
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int RETAINED_LINES_PER_RULE = 3;
    private static final int MAX_ALERT_LINE_CHARACTERS = 2_000;
    private static final int MAX_ALERT_RULE_NAME_CHARACTERS = 200;
    private static final int MAX_ALERT_RULES = 50;
    private static final int MAX_ALERT_TEXT_CHARACTERS = 100_000;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final AtomicBoolean BEEP_IN_PROGRESS = new AtomicBoolean();

    @FunctionalInterface
    interface FxDispatcher {
        void dispatch(Runnable task);
    }

    interface DialogPresenter {
        DialogHandle show(Stage owner, LogAlertAccumulator.Snapshot snapshot, Runnable onHidden);
    }

    interface DialogHandle {
        void update(LogAlertAccumulator.Snapshot snapshot);
        void close();
    }

    interface WakeUpScheduler extends AutoCloseable {
        ScheduledTask schedule(Runnable action, Duration delay);
        @Override void close();
    }

    @FunctionalInterface
    interface ScheduledTask {
        void cancel();
    }

    private final FxDispatcher fxDispatcher;
    private final BooleanSupplier fxThreadChecker;
    private final Clock clock;
    private final DialogPresenter presenter;
    private final WakeUpScheduler scheduler;
    private final Runnable beeper;
    private final LogAlertAccumulator accumulator =
            new LogAlertAccumulator(COOLDOWN, RETAINED_LINES_PER_RULE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object pendingMatchLock = new Object();
    private final LinkedHashMap<String, PendingRuleMatches> pendingMatches = new LinkedHashMap<>();
    private final Object wakeUpLock = new Object();

    private volatile Stage owner;
    private volatile BooleanSupplier soundEnabledSupplier;
    private DialogHandle dialog;
    private ScheduledTask wakeUpTask;
    private Instant scheduledWakeUp;
    private long scheduledAlertGeneration = -1;
    private long wakeUpGeneration;
    private long alertGeneration;
    private long activeGeneration;
    private boolean alertFlushScheduled;

    public LogMonitorAlertService() {
        this(Platform::runLater, Platform::isFxApplicationThread,
                Clock.systemUTC(), () -> true,
                new JavaFxDialogPresenter(), new ExecutorWakeUpScheduler(),
                LogMonitorAlertService::beep);
    }

    LogMonitorAlertService(FxDispatcher fxDispatcher, Clock clock,
                           BooleanSupplier soundEnabledSupplier,
                           DialogPresenter presenter, WakeUpScheduler scheduler,
                           Runnable beeper) {
        this(fxDispatcher, () -> false, clock, soundEnabledSupplier,
                presenter, scheduler, beeper);
    }

    LogMonitorAlertService(FxDispatcher fxDispatcher, BooleanSupplier fxThreadChecker,
                           Clock clock, BooleanSupplier soundEnabledSupplier,
                           DialogPresenter presenter, WakeUpScheduler scheduler,
                           Runnable beeper) {
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher");
        this.fxThreadChecker = Objects.requireNonNull(fxThreadChecker, "fxThreadChecker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.soundEnabledSupplier = Objects.requireNonNull(soundEnabledSupplier, "soundEnabledSupplier");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.beeper = Objects.requireNonNull(beeper, "beeper");
    }

    public void accept(LogMonitorMatch match) {
        Objects.requireNonNull(match, "match");
        acceptAll(List.of(match));
    }

    public void acceptAll(List<LogMonitorMatch> matches) {
        List<LogMonitorMatch> snapshot = List.copyOf(Objects.requireNonNull(matches, "matches"));
        if (snapshot.isEmpty()) {
            return;
        }
        if (closed.get()) {
            return;
        }
        enqueueMatches(snapshot);
    }

    public void setOwner(Stage owner) {
        this.owner = owner;
    }

    public void setSoundEnabledSupplier(BooleanSupplier supplier) {
        soundEnabledSupplier = supplier == null ? () -> true : supplier;
    }

    public void reset() {
        long generation;
        synchronized (pendingMatchLock) {
            if (closed.get()) {
                return;
            }
            generation = ++alertGeneration;
            pendingMatches.clear();
            alertFlushScheduled = false;
        }
        cancelWakeUp();
        dispatchOrRun(() -> resetOnFx(generation));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long generation;
        synchronized (pendingMatchLock) {
            generation = ++alertGeneration;
            pendingMatches.clear();
            alertFlushScheduled = false;
        }
        cancelWakeUp();
        scheduler.close();
        Runnable closeAction = () -> {
            activeGeneration = generation;
            accumulator.reset();
            closeDialog();
        };
        if (fxThreadChecker.getAsBoolean()) {
            closeAction.run();
        } else {
            try {
                fxDispatcher.dispatch(closeAction);
            } catch (IllegalStateException ignored) {
                dialog = null;
            }
        }
    }

    private void enqueueMatches(List<LogMonitorMatch> matches) {
        long generation = -1;
        synchronized (pendingMatchLock) {
            if (closed.get()) {
                return;
            }
            for (LogMonitorMatch match : matches) {
                PendingRuleMatches pending = pendingMatches.computeIfAbsent(
                        Objects.requireNonNull(match, "match").ruleId(),
                        ignored -> new PendingRuleMatches(match));
                pending.add(match);
            }
            if (!alertFlushScheduled) {
                alertFlushScheduled = true;
                generation = alertGeneration;
            }
        }
        if (generation >= 0) {
            long scheduledGeneration = generation;
            if (!dispatchToFx(() -> flushPendingMatches(scheduledGeneration))) {
                synchronized (pendingMatchLock) {
                    if (scheduledGeneration == alertGeneration) {
                        alertFlushScheduled = false;
                    }
                }
            }
        }
    }

    private void flushPendingMatches(long generation) {
        List<LogAlertAccumulator.MatchSummary> summaries;
        synchronized (pendingMatchLock) {
            if (closed.get() || generation != alertGeneration) {
                return;
            }
            summaries = pendingMatches.values().stream()
                    .map(PendingRuleMatches::snapshot)
                    .toList();
            pendingMatches.clear();
            alertFlushScheduled = false;
        }
        if (summaries.isEmpty()) {
            return;
        }
        switchGeneration(generation);
        apply(accumulator.onSummaries(summaries, clock.instant()), generation);
    }

    private void resetOnFx(long generation) {
        if (isCurrentGeneration(generation)) {
            switchGeneration(generation);
        }
    }

    private void switchGeneration(long generation) {
        if (activeGeneration == generation) {
            return;
        }
        DialogHandle previousDialog = dialog;
        dialog = null;
        activeGeneration = generation;
        accumulator.reset();
        if (previousDialog != null) {
            previousDialog.close();
        }
    }

    private void apply(LogAlertAccumulator.Change change, long generation) {
        if (closed.get() || !isCurrentGeneration(generation)
                || generation != activeGeneration) {
            return;
        }
        switch (change.action()) {
            case SHOW -> showOrUpdate(change.snapshot(), generation);
            case UPDATE -> updateOrShow(change.snapshot(), generation);
            case NONE -> { }
        }
        scheduleWakeUp(change.nextWakeUp(), generation);
    }

    private void showOrUpdate(LogAlertAccumulator.Snapshot snapshot, long generation) {
        if (dialog != null) {
            dialog.update(snapshot);
            return;
        }
        dialog = presenter.show(owner, snapshot, () -> dialogHidden(generation));
        playSound();
    }

    private void updateOrShow(LogAlertAccumulator.Snapshot snapshot, long generation) {
        if (dialog == null) {
            showOrUpdate(snapshot, generation);
            return;
        }
        dialog.update(snapshot);
    }

    private void dialogHidden(long generation) {
        if (closed.get() || generation != activeGeneration
                || !isCurrentGeneration(generation)) {
            return;
        }
        dialog = null;
        apply(accumulator.onDialogClosed(clock.instant()), generation);
    }

    private void scheduleWakeUp(Optional<Instant> nextWakeUp, long generation) {
        synchronized (wakeUpLock) {
            if (closed.get() || !isCurrentGeneration(generation)) {
                return;
            }
            if (nextWakeUp.isEmpty()) {
                cancelWakeUpLocked();
                return;
            }
            Instant target = nextWakeUp.orElseThrow();
            if (wakeUpTask != null && target.equals(scheduledWakeUp)
                    && scheduledAlertGeneration == generation) {
                return;
            }
            cancelWakeUpLocked();
            Duration delay = Duration.between(clock.instant(), target);
            if (delay.isNegative()) {
                delay = Duration.ZERO;
            }
            long token = ++wakeUpGeneration;
            scheduledWakeUp = target;
            scheduledAlertGeneration = generation;
            try {
                wakeUpTask = scheduler.schedule(() -> wakeUp(token, generation), delay);
            } catch (RejectedExecutionException ignored) {
                scheduledWakeUp = null;
                scheduledAlertGeneration = -1;
            }
        }
    }

    private void wakeUp(long token, long generation) {
        synchronized (wakeUpLock) {
            if (closed.get() || token != wakeUpGeneration
                    || generation != scheduledAlertGeneration
                    || !isCurrentGeneration(generation)) {
                return;
            }
            wakeUpTask = null;
            scheduledWakeUp = null;
            scheduledAlertGeneration = -1;
        }
        dispatchToFx(() -> {
            if (isCurrentGeneration(generation)) {
                apply(accumulator.cooldownElapsed(clock.instant()), generation);
            }
        });
    }

    private void cancelWakeUp() {
        synchronized (wakeUpLock) {
            cancelWakeUpLocked();
        }
    }

    private void cancelWakeUpLocked() {
        wakeUpGeneration++;
        if (wakeUpTask != null) {
            wakeUpTask.cancel();
            wakeUpTask = null;
        }
        scheduledWakeUp = null;
        scheduledAlertGeneration = -1;
    }

    private boolean isCurrentGeneration(long generation) {
        synchronized (pendingMatchLock) {
            return !closed.get() && generation == alertGeneration;
        }
    }

    private void dispatchOrRun(Runnable action) {
        if (fxThreadChecker.getAsBoolean()) {
            action.run();
        } else {
            dispatchToFx(action);
        }
    }

    private void closeDialog() {
        DialogHandle previousDialog = dialog;
        dialog = null;
        if (previousDialog != null) {
            previousDialog.close();
        }
    }

    private boolean dispatchToFx(Runnable action) {
        try {
            fxDispatcher.dispatch(() -> {
                if (!closed.get()) {
                    action.run();
                }
            });
            return true;
        } catch (IllegalStateException ignored) {
            // JavaFX is shutting down; close() will release the scheduler.
            return false;
        }
    }

    private void playSound() {
        try {
            if (soundEnabledSupplier.getAsBoolean()) {
                beeper.run();
            }
        } catch (RuntimeException ignored) {
            // Alert visibility must not depend on desktop audio availability.
        }
    }

    private static void beep() {
        if (!BEEP_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                desktopBeep();
            } finally {
                BEEP_IN_PROGRESS.set(false);
            }
        }, "log-monitor-alert-sound");
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (RuntimeException exception) {
            BEEP_IN_PROGRESS.set(false);
        }
    }

    private static void desktopBeep() {
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (HeadlessException | SecurityException ignored) {
            // No desktop audio is available.
        }
    }

    static String format(LogAlertAccumulator.Snapshot snapshot) {
        StringBuilder text = new StringBuilder();
        int displayedRules = 0;
        for (LogAlertAccumulator.RuleSummary summary : snapshot.rules()) {
            if (displayedRules >= MAX_ALERT_RULES) {
                break;
            }
            String section = formatRule(summary);
            int separatorLength = text.isEmpty() ? 0 : 2;
            if (text.length() + separatorLength + section.length()
                    > MAX_ALERT_TEXT_CHARACTERS) {
                break;
            }
            if (separatorLength > 0) {
                text.append("\n\n");
            }
            text.append(section);
            displayedRules++;
        }
        int omittedRules = snapshot.rules().size() - displayedRules;
        if (omittedRules > 0) {
            text.append("\n\n其余 ")
                    .append(omittedRules)
                    .append(" 条规则详情已省略；本次共 ")
                    .append(snapshot.totalCount())
                    .append(" 次命中");
        }
        return text.toString();
    }

    private static String formatRule(LogAlertAccumulator.RuleSummary summary) {
        StringBuilder text = new StringBuilder();
        text.append(abbreviate(summary.ruleName(), MAX_ALERT_RULE_NAME_CHARACTERS))
                    .append("  ·  ")
                    .append(summary.count())
                    .append(" 次\n首次: ")
                    .append(TIME_FORMATTER.format(summary.firstMatchedAt()))
                    .append("    最近: ")
                    .append(TIME_FORMATTER.format(summary.lastMatchedAt()));
        for (String line : summary.recentLines()) {
            text.append("\n").append(abbreviate(line, MAX_ALERT_LINE_CHARACTERS));
        }
        return text.toString();
    }

    private static String abbreviate(String value, int maxCharacters) {
        String text = value == null ? "" : value;
        if (text.length() <= maxCharacters) {
            return text;
        }
        int endIndex = maxCharacters;
        if (Character.isHighSurrogate(text.charAt(endIndex - 1))
                && Character.isLowSurrogate(text.charAt(endIndex))) {
            endIndex--;
        }
        return text.substring(0, endIndex) + "...[已截断]";
    }

    private static final class PendingRuleMatches {
        private final String ruleId;
        private String ruleName;
        private String expression;
        private long count;
        private Instant firstMatchedAt;
        private Instant lastMatchedAt;
        private final ArrayDeque<String> recentLines = new ArrayDeque<>();

        private PendingRuleMatches(LogMonitorMatch match) {
            ruleId = Objects.requireNonNull(match.ruleId(), "match.ruleId");
            ruleName = Objects.requireNonNull(match.ruleName(), "match.ruleName");
            expression = Objects.requireNonNull(match.expression(), "match.expression");
        }

        private void add(LogMonitorMatch match) {
            ruleName = Objects.requireNonNull(match.ruleName(), "match.ruleName");
            expression = Objects.requireNonNull(match.expression(), "match.expression");
            Instant matchedAt = Objects.requireNonNull(match.matchedAt(), "match.matchedAt");
            if (count == 0 || matchedAt.isBefore(firstMatchedAt)) {
                firstMatchedAt = matchedAt;
            }
            if (lastMatchedAt == null || matchedAt.isAfter(lastMatchedAt)) {
                lastMatchedAt = matchedAt;
            }
            count = count == Long.MAX_VALUE ? Long.MAX_VALUE : count + 1;
            recentLines.addLast(abbreviate(
                    Objects.requireNonNull(match.line(), "match.line"),
                    MAX_ALERT_LINE_CHARACTERS));
            while (recentLines.size() > RETAINED_LINES_PER_RULE) {
                recentLines.removeFirst();
            }
        }

        private LogAlertAccumulator.MatchSummary snapshot() {
            return new LogAlertAccumulator.MatchSummary(
                    ruleId, ruleName, expression, count,
                    firstMatchedAt, lastMatchedAt, new ArrayList<>(recentLines));
        }
    }

    private static final class JavaFxDialogPresenter implements DialogPresenter {
        @Override
        public DialogHandle show(Stage owner, LogAlertAccumulator.Snapshot snapshot, Runnable onHidden) {
            Dialog<ButtonType> alert = new Dialog<>();
            alert.initModality(Modality.NONE);
            FxTheme.apply(alert);
            alert.setTitle("日志监控提醒");
            alert.setHeaderText("检测到需要关注的日志");
            alert.setResizable(true);
            alert.getDialogPane().getStyleClass().add("log-monitor-alert-dialog");
            if (owner != null && owner.isShowing() && !owner.isIconified()) {
                alert.initOwner(owner);
            }

            TextArea summaryArea = new TextArea(format(snapshot));
            summaryArea.setEditable(false);
            summaryArea.setWrapText(true);
            summaryArea.setPrefColumnCount(72);
            summaryArea.setPrefRowCount(14);
            summaryArea.getStyleClass().add("log-monitor-alert-summary");
            alert.getDialogPane().setContent(summaryArea);
            alert.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
            alert.setOnShown(event -> {
                if (alert.getDialogPane().getScene().getWindow() instanceof Stage alertStage) {
                    alertStage.setAlwaysOnTop(true);
                }
            });
            alert.setOnHidden(event -> onHidden.run());
            alert.show();

            return new DialogHandle() {
                @Override
                public void update(LogAlertAccumulator.Snapshot updated) {
                    summaryArea.setText(format(updated));
                    summaryArea.positionCaret(0);
                }

                @Override
                public void close() {
                    alert.close();
                }
            };
        }
    }

    private static final class ExecutorWakeUpScheduler implements WakeUpScheduler {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "log-monitor-alert-cooldown");
            thread.setDaemon(true);
            return thread;
        });

        @Override
        public ScheduledTask schedule(Runnable action, Duration delay) {
            ScheduledFuture<?> future = executor.schedule(
                    action, Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
