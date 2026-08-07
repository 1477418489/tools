package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import plugin.javafxtools.model.MemoReminder;
import plugin.javafxtools.model.ReminderScheduleMode;
import plugin.javafxtools.util.FxTheme;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 备忘提醒任务调度和提醒弹窗处理。
 *
 * @author wwj
 */
public class MemoReminderSchedulerService {
    private static final long SNOOZE_MILLIS = 5 * 60_000L;

    private final ConcurrentMap<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Dialog<ButtonType>> openDialogs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "memo-reminder-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final TableView<MemoReminder> reminderTable;
    private final BooleanSupplier persistAction;
    private final Runnable stateRefreshAction;
    private final Consumer<String> infoLogger;
    private volatile BooleanSupplier soundEnabledSupplier = () -> true;
    private volatile boolean closed;

    /**
     * 创建提醒调度服务。
     *
     * @param reminderTable 提醒表格
     * @param persistAction 持久化回调
     * @param stateRefreshAction 状态刷新回调
     * @param infoLogger 信息日志回调
     */
    public MemoReminderSchedulerService(TableView<MemoReminder> reminderTable,
                                        BooleanSupplier persistAction,
                                        Runnable stateRefreshAction,
                                        Consumer<String> infoLogger) {
        this.reminderTable = reminderTable;
        this.persistAction = persistAction;
        this.stateRefreshAction = stateRefreshAction;
        this.infoLogger = infoLogger;
    }

    /**
     * 为指定提醒排队下一次弹窗任务。
     *
     * @param reminder 要调度的提醒
     */
    public void scheduleReminder(MemoReminder reminder) {
        if (closed || !reminder.isActive() || reminder.getRemainingTimes() == 0) {
            return;
        }
        long delay = Math.max(0L, reminder.getNextTriggerEpochMillis() - System.currentTimeMillis());
        scheduleAfter(reminder, delay);
    }

    /**
     * 取消指定提醒的排队任务。
     *
     * @param reminderId 提醒 ID
     */
    public void cancelTask(long reminderId) {
        ScheduledFuture<?> task = scheduledTasks.remove(reminderId);
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 清理提醒调度任务和后台线程。
     */
    public void cleanup() {
        closed = true;
        scheduledTasks.values().forEach(task -> task.cancel(false));
        scheduledTasks.clear();
        scheduler.shutdownNow();
        closeOpenDialogs();
    }

    public void setSoundEnabledSupplier(BooleanSupplier soundEnabledSupplier) {
        this.soundEnabledSupplier = soundEnabledSupplier == null ? () -> true : soundEnabledSupplier;
    }

    private void showReminderDialog(MemoReminder reminder) {
        if (closed || openDialogs.containsKey(reminder.getId())) {
            return;
        }
        try {
            Platform.runLater(() -> {
                if (closed || !reminder.isActive()
                        || !reminderTable.getItems().contains(reminder)
                        || openDialogs.containsKey(reminder.getId())) {
                    return;
                }
                Dialog<ButtonType> dialog = new Dialog<>();
                FxTheme.apply(dialog);
                dialog.setTitle("备忘提醒");
                dialog.setHeaderText(reminder.getScheduleMode() == ReminderScheduleMode.AT_TIME
                        ? "定时闹钟"
                        : "周期提醒");
                dialog.getDialogPane().getStyleClass().add("reminder-dialog");
                configureDialogWindow(dialog);

                Label contentLabel = new Label(reminder.getContent());
                contentLabel.setWrapText(true);
                contentLabel.getStyleClass().add("reminder-dialog-content");
                CheckBox doneBox = new CheckBox(
                        reminder.getScheduleMode() == ReminderScheduleMode.AT_TIME
                                ? "已处理，完成此闹钟"
                                : "已处理，进入下个周期提醒（消耗1次）");
                VBox box = new VBox(10, contentLabel, doneBox);
                dialog.getDialogPane().setContent(box);
                ButtonType confirm = new ButtonType("确认", ButtonBar.ButtonData.OK_DONE);
                ButtonType snooze = new ButtonType("稍后5分钟", ButtonBar.ButtonData.OTHER);
                dialog.getDialogPane().getButtonTypes().setAll(
                        confirm, snooze, ButtonType.CLOSE);

                openDialogs.put(reminder.getId(), dialog);
                try {
                    playReminderSound();
                    Optional<ButtonType> result = dialog.showAndWait();
                    handleDialogResult(
                            reminder, result.orElse(ButtonType.CLOSE), doneBox.isSelected());
                } finally {
                    openDialogs.remove(reminder.getId(), dialog);
                }
            });
        } catch (IllegalStateException ignored) {
            // JavaFX 运行时正在关闭，不再展示提醒。
        }
    }

    private void playReminderSound() {
        if (!soundEnabledSupplier.getAsBoolean()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (HeadlessException | SecurityException ignored) {
            // 无桌面音频环境时仍正常显示提醒。
        }
    }

    private void handleDialogResult(MemoReminder reminder, ButtonType buttonType, boolean checkedDone) {
        if (closed || !reminderTable.getItems().contains(reminder)) {
            cancelTask(reminder.getId());
            return;
        }

        ReminderState previousState = ReminderState.capture(reminder);
        String successMessage;
        ButtonBar.ButtonData buttonData = buttonType.getButtonData();
        if (shouldCompleteReminder(buttonData, checkedDone)) {
            successMessage = updateDoneReminder(reminder);
        } else {
            reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + SNOOZE_MILLIS);
            successMessage = buttonData == ButtonBar.ButtonData.OTHER
                    ? "提醒已稍后5分钟: " + reminder.getContent()
                    : "提醒未确认处理，已稍后5分钟: " + reminder.getContent();
        }

        if (!persistAction.getAsBoolean()) {
            previousState.restore(reminder);
            reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + SNOOZE_MILLIS);
            scheduleReminder(reminder);
            refreshState();
            return;
        }

        if (reminder.isActive() && reminder.getRemainingTimes() != 0) {
            scheduleReminder(reminder);
        } else {
            cancelTask(reminder.getId());
        }
        infoLogger.accept(successMessage);
        refreshState();
    }

    static boolean shouldCompleteReminder(ButtonBar.ButtonData buttonData, boolean checkedDone) {
        return buttonData == ButtonBar.ButtonData.OK_DONE && checkedDone;
    }

    private String updateDoneReminder(MemoReminder reminder) {
        boolean completed = advanceAfterCompletion(reminder, System.currentTimeMillis());
        if (completed) {
            return "提醒已完成: " + reminder.getContent();
        }
        return "提醒确认，已进入下个周期: " + reminder.getContent();
    }

    static boolean advanceAfterCompletion(MemoReminder reminder, long currentTimeMillis) {
        if (reminder.getRemainingTimes() > 0) {
            reminder.setRemainingTimes(reminder.getRemainingTimes() - 1);
        }
        if (reminder.getRemainingTimes() == 0) {
            reminder.setActive(false);
            return true;
        }

        if (reminder.getScheduleMode() == ReminderScheduleMode.AT_TIME) {
            reminder.setRemainingTimes(0);
            reminder.setActive(false);
            return true;
        }

        reminder.setNextTriggerEpochMillis(currentTimeMillis + reminder.intervalMillis());
        return false;
    }

    private void scheduleAfter(MemoReminder reminder, long delayMillis) {
        cancelTask(reminder.getId());
        if (closed || scheduler.isShutdown()) {
            return;
        }
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> showReminderDialog(reminder), delayMillis, TimeUnit.MILLISECONDS);
            scheduledTasks.put(reminder.getId(), future);
        } catch (RejectedExecutionException ignored) {
            // 页面关闭期间无需重新创建提醒任务。
        }
    }

    private void refreshState() {
        reminderTable.refresh();
        stateRefreshAction.run();
    }

    private void configureDialogWindow(Dialog<ButtonType> dialog) {
        Window owner = reminderTable.getScene() == null
                ? null
                : reminderTable.getScene().getWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setOnShown(event -> {
            if (owner instanceof Stage ownerStage) {
                ownerStage.show();
                ownerStage.setIconified(false);
                ownerStage.toFront();
            }
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage dialogStage) {
                dialogStage.setAlwaysOnTop(true);
                dialogStage.toFront();
                dialogStage.requestFocus();
            }
        });
    }

    private void closeOpenDialogs() {
        Runnable closeAction = () -> List.copyOf(openDialogs.values()).forEach(Dialog::close);
        if (Platform.isFxApplicationThread()) {
            closeAction.run();
            return;
        }
        try {
            Platform.runLater(closeAction);
        } catch (IllegalStateException ignored) {
            openDialogs.clear();
        }
    }

    private record ReminderState(int remainingTimes, long nextTriggerEpochMillis, boolean active) {
        private static ReminderState capture(MemoReminder reminder) {
            return new ReminderState(reminder.getRemainingTimes(),
                    reminder.getNextTriggerEpochMillis(), reminder.isActive());
        }

        private void restore(MemoReminder reminder) {
            reminder.setRemainingTimes(remainingTimes);
            reminder.setNextTriggerEpochMillis(nextTriggerEpochMillis);
            reminder.setActive(active);
        }
    }
}
