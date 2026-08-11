package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
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
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

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
    private final Clock clock;
    private final DialogPresenter presenter;
    private final WakeUpScheduler scheduler;
    private final Runnable beeper;
    private final LogAlertAccumulator accumulator =
            new LogAlertAccumulator(COOLDOWN, RETAINED_LINES_PER_RULE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object wakeUpLock = new Object();

    private volatile Stage owner;
    private volatile BooleanSupplier soundEnabledSupplier;
    private DialogHandle dialog;
    private ScheduledTask wakeUpTask;
    private Instant scheduledWakeUp;
    private long wakeUpGeneration;

    public LogMonitorAlertService() {
        this(Platform::runLater, Clock.systemUTC(), () -> true,
                new JavaFxDialogPresenter(), new ExecutorWakeUpScheduler(),
                LogMonitorAlertService::beep);
    }

    LogMonitorAlertService(FxDispatcher fxDispatcher, Clock clock,
                           BooleanSupplier soundEnabledSupplier,
                           DialogPresenter presenter, WakeUpScheduler scheduler,
                           Runnable beeper) {
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.soundEnabledSupplier = Objects.requireNonNull(soundEnabledSupplier, "soundEnabledSupplier");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.beeper = Objects.requireNonNull(beeper, "beeper");
    }

    public void accept(LogMonitorMatch match) {
        Objects.requireNonNull(match, "match");
        if (closed.get()) {
            return;
        }
        dispatchToFx(() -> apply(accumulator.onMatch(match, clock.instant())));
    }

    public void setOwner(Stage owner) {
        this.owner = owner;
    }

    public void setSoundEnabledSupplier(BooleanSupplier supplier) {
        soundEnabledSupplier = supplier == null ? () -> true : supplier;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelWakeUp();
        scheduler.close();
        try {
            fxDispatcher.dispatch(() -> {
                if (dialog != null) {
                    dialog.close();
                    dialog = null;
                }
            });
        } catch (IllegalStateException ignored) {
            dialog = null;
        }
    }

    private void apply(LogAlertAccumulator.Change change) {
        if (closed.get()) {
            return;
        }
        switch (change.action()) {
            case SHOW -> showOrUpdate(change.snapshot());
            case UPDATE -> updateOrShow(change.snapshot());
            case NONE -> { }
        }
        scheduleWakeUp(change.nextWakeUp());
    }

    private void showOrUpdate(LogAlertAccumulator.Snapshot snapshot) {
        if (dialog != null) {
            dialog.update(snapshot);
            return;
        }
        dialog = presenter.show(owner, snapshot, this::dialogHidden);
        playSound();
    }

    private void updateOrShow(LogAlertAccumulator.Snapshot snapshot) {
        if (dialog == null) {
            showOrUpdate(snapshot);
            return;
        }
        dialog.update(snapshot);
    }

    private void dialogHidden() {
        if (closed.get()) {
            return;
        }
        dialog = null;
        apply(accumulator.onDialogClosed(clock.instant()));
    }

    private void scheduleWakeUp(Optional<Instant> nextWakeUp) {
        synchronized (wakeUpLock) {
            if (closed.get()) {
                return;
            }
            if (nextWakeUp.isEmpty()) {
                cancelWakeUpLocked();
                return;
            }
            Instant target = nextWakeUp.orElseThrow();
            if (wakeUpTask != null && target.equals(scheduledWakeUp)) {
                return;
            }
            cancelWakeUpLocked();
            Duration delay = Duration.between(clock.instant(), target);
            if (delay.isNegative()) {
                delay = Duration.ZERO;
            }
            long token = ++wakeUpGeneration;
            scheduledWakeUp = target;
            try {
                wakeUpTask = scheduler.schedule(() -> wakeUp(token), delay);
            } catch (RejectedExecutionException ignored) {
                scheduledWakeUp = null;
            }
        }
    }

    private void wakeUp(long token) {
        synchronized (wakeUpLock) {
            if (closed.get() || token != wakeUpGeneration) {
                return;
            }
            wakeUpTask = null;
            scheduledWakeUp = null;
        }
        dispatchToFx(() -> apply(accumulator.cooldownElapsed(clock.instant())));
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
    }

    private void dispatchToFx(Runnable action) {
        try {
            fxDispatcher.dispatch(() -> {
                if (!closed.get()) {
                    action.run();
                }
            });
        } catch (IllegalStateException ignored) {
            // JavaFX is shutting down; close() will release the scheduler.
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
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (HeadlessException | SecurityException ignored) {
            // No desktop audio is available.
        }
    }

    private static String format(LogAlertAccumulator.Snapshot snapshot) {
        StringBuilder text = new StringBuilder();
        for (LogAlertAccumulator.RuleSummary summary : snapshot.rules()) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(summary.ruleName())
                    .append("  ·  ")
                    .append(summary.count())
                    .append(" 次\n首次: ")
                    .append(TIME_FORMATTER.format(summary.firstMatchedAt()))
                    .append("    最近: ")
                    .append(TIME_FORMATTER.format(summary.lastMatchedAt()));
            for (String line : summary.recentLines()) {
                text.append("\n").append(line);
            }
        }
        return text.toString();
    }

    private static final class JavaFxDialogPresenter implements DialogPresenter {
        @Override
        public DialogHandle show(Stage owner, LogAlertAccumulator.Snapshot snapshot, Runnable onHidden) {
            Dialog<ButtonType> alert = new Dialog<>();
            FxTheme.apply(alert);
            alert.setTitle("日志监控提醒");
            alert.setHeaderText("检测到需要关注的日志");
            alert.setResizable(true);
            alert.getDialogPane().getStyleClass().add("log-monitor-alert-dialog");
            if (owner != null) {
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
                if (owner != null) {
                    owner.show();
                    owner.setIconified(false);
                    owner.toFront();
                }
                if (alert.getDialogPane().getScene().getWindow() instanceof Stage alertStage) {
                    alertStage.setAlwaysOnTop(true);
                    alertStage.toFront();
                    alertStage.requestFocus();
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
