package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.model.IntervalUnit;
import plugin.javafxtools.model.MemoReminder;
import plugin.javafxtools.service.MemoReminderSchedulerService;
import plugin.javafxtools.service.MemoReminderStore;
import plugin.javafxtools.service.MemoReminderTableSupport;
import plugin.javafxtools.util.LogTextTrimmer;
import plugin.javafxtools.util.TimeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 备忘提醒页签控制器，负责 FXML 事件入口、表单校验和服务装配。
 */
public class MemoReminderController extends BaseController {
    private static final int MAX_LOG_LINES = 400;

    private final ObservableList<MemoReminder> reminders = FXCollections.observableArrayList();
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());
    private final MemoReminderStore reminderStore = new MemoReminderStore();

    @FXML
    private TextArea memoInput;

    @FXML
    private TextField intervalField;

    @FXML
    private ComboBox<IntervalUnit> intervalUnitBox;

    @FXML
    private TextField timesField;

    @FXML
    private TableView<MemoReminder> reminderTable;

    @FXML
    private TableColumn<MemoReminder, String> contentCol;

    @FXML
    private TableColumn<MemoReminder, String> intervalCol;

    @FXML
    private TableColumn<MemoReminder, String> remainCol;

    @FXML
    private TableColumn<MemoReminder, String> nextTimeCol;

    @FXML
    private TableColumn<MemoReminder, String> statusCol;

    @FXML
    private TextArea logArea;

    private MemoReminderSchedulerService schedulerService;

    /**
     * 初始化备忘提醒页签。
     */
    @FXML
    public void initialize() {
        intervalUnitBox.setItems(FXCollections.observableArrayList(IntervalUnit.values()));
        intervalUnitBox.setValue(IntervalUnit.MINUTES);
        timesField.setText("1");
        new MemoReminderTableSupport(reminderTable, contentCol, intervalCol,
                remainCol, nextTimeCol, statusCol, reminders).initialize();
        schedulerService = new MemoReminderSchedulerService(reminderTable, this::persist, this::info);
        loadFromDisk();
        reminders.forEach(schedulerService::scheduleReminder);
        info("备忘提醒模块初始化完成");
    }

    /**
     * 新增备忘提醒。
     */
    @FXML
    private void addReminder() {
        String content = memoInput.getText() == null ? "" : memoInput.getText().trim();
        if (content.isEmpty()) {
            error("备忘内容不能为空");
            return;
        }

        Integer interval = parsePositiveNumber(intervalField.getText(), "提醒间隔必须大于0");
        Integer times = parseNumber(timesField.getText());
        if (interval == null || times == null) {
            if (times == null) {
                error("间隔/次数请输入有效数字");
            }
            return;
        }

        MemoReminder reminder = new MemoReminder(idGenerator.incrementAndGet(), content,
                interval, intervalUnitBox.getValue(), times);
        reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + reminder.intervalMillis());
        reminders.add(reminder);
        schedulerService.scheduleReminder(reminder);
        persist();
        reminderTable.refresh();
        info("新增备忘提醒: " + content);
    }

    /**
     * 删除当前选中的备忘提醒。
     */
    @FXML
    private void removeSelected() {
        MemoReminder selected = getSelectedReminder();
        if (selected == null) {
            return;
        }
        schedulerService.cancelTask(selected.getId());
        reminders.remove(selected);
        persist();
        info("已删除备忘提醒: " + selected.getContent());
    }

    /**
     * 暂停当前选中的备忘提醒。
     */
    @FXML
    private void pauseSelected() {
        MemoReminder selected = getSelectedReminder();
        if (selected == null) {
            return;
        }
        selected.setActive(false);
        schedulerService.cancelTask(selected.getId());
        persist();
        reminderTable.refresh();
        info("已暂停提醒: " + selected.getContent());
    }

    /**
     * 恢复当前选中的备忘提醒。
     */
    @FXML
    private void resumeSelected() {
        MemoReminder selected = getSelectedReminder();
        if (selected == null) {
            return;
        }
        if (selected.getRemainingTimes() == 0) {
            error("该提醒次数已耗尽，请新建提醒");
            return;
        }
        selected.setActive(true);
        if (selected.getNextTriggerEpochMillis() <= 0) {
            selected.setNextTriggerEpochMillis(System.currentTimeMillis() + selected.intervalMillis());
        }
        schedulerService.scheduleReminder(selected);
        persist();
        reminderTable.refresh();
        info("已恢复提醒: " + selected.getContent());
    }

    /**
     * 记录备忘提醒模块日志。
     *
     * @param level 日志级别
     * @param message 日志内容
     */
    @Override
    public void log(String level, String message) {
        String formatted = String.format("[%s][%s][备忘提醒] %s",
                TimeUtils.getCurrentDateTime(), level, message);
        Platform.runLater(() -> {
            if (logArea != null && logArea.getScene() != null) {
                LogTextTrimmer.trimToMaxLines(logArea, MAX_LOG_LINES, 80);
                logArea.appendText(formatted + "\n");
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    /**
     * 获取备忘提醒模块日志输出区域。
     *
     * @return 日志输出区域
     */
    @Override
    public TextArea getLogArea() {
        return logArea;
    }

    /**
     * 清理提醒调度任务和后台线程。
     */
    public void cleanup() {
        if (schedulerService != null) {
            schedulerService.cleanup();
        }
    }

    private void loadFromDisk() {
        try {
            List<MemoReminder> loaded = reminderStore.load();
            reminders.setAll(loaded);
            long maxId = reminders.stream().mapToLong(MemoReminder::getId)
                    .max().orElse(System.currentTimeMillis());
            idGenerator.set(maxId);
            info("已加载备忘提醒数量: " + reminders.size());
        } catch (Exception e) {
            error("加载备忘提醒失败: " + e.getMessage());
        }
    }

    private void persist() {
        try {
            reminderStore.save(new ArrayList<>(reminders));
        } catch (IOException e) {
            error("保存备忘提醒失败: " + e.getMessage());
        }
    }

    private MemoReminder getSelectedReminder() {
        MemoReminder selected = reminderTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            error("请先选择一条备忘提醒");
        }
        return selected;
    }

    private Integer parsePositiveNumber(String text, String errorMessage) {
        Integer value = parseNumber(text);
        if (value == null) {
            error("间隔/次数请输入有效数字");
            return null;
        }
        if (value <= 0) {
            error(errorMessage);
            return null;
        }
        return value;
    }

    private Integer parseNumber(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
