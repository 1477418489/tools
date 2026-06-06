package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import plugin.javafxtools.model.MemoReminder;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 备忘提醒任务调度和提醒弹窗处理。
 *
 * @author wwj
 */
public class MemoReminderSchedulerService {
    private static final long SNOOZE_MILLIS = 5 * 60_000L;

    private final ConcurrentMap<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "memo-reminder-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final TableView<MemoReminder> reminderTable;
    private final Runnable persistAction;
    private final Consumer<String> infoLogger;

    /**
     * 创建提醒调度服务。
     *
     * @param reminderTable 提醒表格
     * @param persistAction 持久化回调
     * @param infoLogger 信息日志回调
     */
    public MemoReminderSchedulerService(TableView<MemoReminder> reminderTable,
                                        Runnable persistAction,
                                        Consumer<String> infoLogger) {
        this.reminderTable = reminderTable;
        this.persistAction = persistAction;
        this.infoLogger = infoLogger;
    }

    /**
     * 为指定提醒排队下一次弹窗任务。
     *
     * @param reminder 要调度的提醒
     */
    public void scheduleReminder(MemoReminder reminder) {
        if (!reminder.isActive() || reminder.getRemainingTimes() == 0) {
            return;
        }
        cancelTask(reminder.getId());

        long delay = Math.max(1000L, reminder.getNextTriggerEpochMillis() - System.currentTimeMillis());
        ScheduledFuture<?> future =
                scheduler.schedule(() -> showReminderDialog(reminder), delay, TimeUnit.MILLISECONDS);
        scheduledTasks.put(reminder.getId(), future);
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
        scheduledTasks.values().forEach(task -> task.cancel(false));
        scheduledTasks.clear();
        scheduler.shutdownNow();
    }

    private void showReminderDialog(MemoReminder reminder) {
        Platform.runLater(() -> {
            if (!reminder.isActive()) {
                return;
            }
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("备忘提醒");
            dialog.setHeaderText("提醒内容");

            Label contentLabel = new Label(reminder.getContent());
            contentLabel.setWrapText(true);
            CheckBox doneBox = new CheckBox("已处理，进入下个周期提醒（消耗1次）");
            VBox box = new VBox(10, contentLabel, doneBox);
            dialog.getDialogPane().setContent(box);
            ButtonType confirm = new ButtonType("确认", ButtonBar.ButtonData.OK_DONE);
            ButtonType snooze = new ButtonType("稍后5分钟", ButtonBar.ButtonData.OTHER);
            dialog.getDialogPane().getButtonTypes().setAll(confirm, snooze, ButtonType.CLOSE);

            Optional<ButtonType> result = dialog.showAndWait();
            handleDialogResult(reminder, result.orElse(ButtonType.CLOSE), doneBox.isSelected());
        });
    }

    private void handleDialogResult(MemoReminder reminder, ButtonType buttonType, boolean checkedDone) {
        if (buttonType.getButtonData() == ButtonBar.ButtonData.OTHER) {
            reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + SNOOZE_MILLIS);
            scheduleReminder(reminder);
            infoLogger.accept("提醒已稍后5分钟: " + reminder.getContent());
            persistAndRefresh();
            return;
        }

        if (checkedDone) {
            handleDoneReminder(reminder);
        } else {
            reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + SNOOZE_MILLIS);
            scheduleReminder(reminder);
            infoLogger.accept("未勾选处理，已自动稍后5分钟提醒: " + reminder.getContent());
        }
        persistAndRefresh();
    }

    private void handleDoneReminder(MemoReminder reminder) {
        if (reminder.getRemainingTimes() > 0) {
            reminder.setRemainingTimes(reminder.getRemainingTimes() - 1);
        }
        if (reminder.getRemainingTimes() == 0) {
            reminder.setActive(false);
            cancelTask(reminder.getId());
            infoLogger.accept("提醒已完成: " + reminder.getContent());
            return;
        }

        reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + reminder.intervalMillis());
        scheduleReminder(reminder);
        infoLogger.accept("提醒确认，已进入下个周期: " + reminder.getContent());
    }

    private void persistAndRefresh() {
        persistAction.run();
        reminderTable.refresh();
    }
}
