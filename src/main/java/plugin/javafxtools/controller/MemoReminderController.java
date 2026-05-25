package plugin.javafxtools.controller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.model.IntervalUnit;
import plugin.javafxtools.model.MemoReminder;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 备忘提醒页签控制器，负责提醒配置、定时调度和弹窗确认。
 */
public class MemoReminderController extends BaseController {
    /**
     * 备忘提醒日志区域最大保留行数。
     */
    private static final int MAX_LOG_LINES = 400;

    /**
     * 稍后提醒的固定延迟时间。
     */
    private static final long SNOOZE_MILLIS = 5 * 60_000L;

    /**
     * 备忘提醒数据持久化文件。
     */
    private static final Path DATA_FILE = Path.of("userData", "memo_reminders.json");

    /**
     * 表格展示的提醒配置集合。
     */
    private final ObservableList<MemoReminder> reminders = FXCollections.observableArrayList();

    /**
     * 已排队的提醒调度任务。
     */
    private final ConcurrentMap<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * 提醒任务后台调度器。
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "memo-reminder-scheduler");
        t.setDaemon(true);
        return t;
    });

    /**
     * 新提醒 ID 生成器。
     */
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());

    /**
     * 备忘提醒 JSON 序列化工具。
     */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 备忘内容输入区。
     */
    @FXML
    private TextArea memoInput;

    /**
     * 提醒间隔输入框。
     */
    @FXML
    private TextField intervalField;

    /**
     * 提醒间隔单位选择框。
     */
    @FXML
    private ComboBox<IntervalUnit> intervalUnitBox;

    /**
     * 提醒次数输入框。
     */
    @FXML
    private TextField timesField;

    /**
     * 备忘提醒列表表格。
     */
    @FXML
    private TableView<MemoReminder> reminderTable;

    /**
     * 备忘内容列。
     */
    @FXML
    private TableColumn<MemoReminder, String> contentCol;

    /**
     * 提醒间隔列。
     */
    @FXML
    private TableColumn<MemoReminder, String> intervalCol;

    /**
     * 剩余次数列。
     */
    @FXML
    private TableColumn<MemoReminder, String> remainCol;

    /**
     * 下次提醒时间列。
     */
    @FXML
    private TableColumn<MemoReminder, String> nextTimeCol;

    /**
     * 运行状态列。
     */
    @FXML
    private TableColumn<MemoReminder, String> statusCol;

    /**
     * 备忘提醒模块日志输出区。
     */
    @FXML
    private TextArea logArea;

    /**
     * 初始化备忘提醒页签。
     */
    @FXML
    public void initialize() {
        intervalUnitBox.setItems(FXCollections.observableArrayList(IntervalUnit.values()));
        intervalUnitBox.setValue(IntervalUnit.MINUTES);
        timesField.setText("1");
        initTable();
        loadFromDisk();
        reminders.forEach(this::scheduleReminder);
        info("备忘提醒模块初始化完成");
    }

    /**
     * 初始化提醒表格列绑定。
     */
    private void initTable() {
        reminderTable.setItems(reminders);
        contentCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getContent()));
        intervalCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getDisplayInterval()));
        remainCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getDisplayRemaining()));
        nextTimeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(formatTime(data.getValue().getNextTriggerEpochMillis())));
        statusCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().isActive() ? "运行中" : "已暂停"));
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

        int interval;
        int times;
        try {
            interval = Integer.parseInt(intervalField.getText().trim());
            times = Integer.parseInt(timesField.getText().trim());
        } catch (Exception e) {
            error("间隔/次数请输入有效数字");
            return;
        }

        if (interval <= 0) {
            error("提醒间隔必须大于0");
            return;
        }

        MemoReminder reminder = new MemoReminder(idGenerator.incrementAndGet(), content, interval,
                intervalUnitBox.getValue(), times);
        reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + reminder.intervalMillis());
        reminders.add(reminder);
        scheduleReminder(reminder);
        persist();
        reminderTable.refresh();
        info("新增备忘提醒: " + content);
    }

    /**
     * 删除当前选中的备忘提醒。
     */
    @FXML
    private void removeSelected() {
        MemoReminder selected = reminderTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            error("请先选择一条备忘提醒");
            return;
        }
        cancelTask(selected.getId());
        reminders.remove(selected);
        persist();
        info("已删除备忘提醒: " + selected.getContent());
    }

    /**
     * 暂停当前选中的备忘提醒。
     */
    @FXML
    private void pauseSelected() {
        MemoReminder selected = reminderTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            error("请先选择一条备忘提醒");
            return;
        }
        selected.setActive(false);
        cancelTask(selected.getId());
        persist();
        reminderTable.refresh();
        info("已暂停提醒: " + selected.getContent());
    }

    /**
     * 恢复当前选中的备忘提醒。
     */
    @FXML
    private void resumeSelected() {
        MemoReminder selected = reminderTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            error("请先选择一条备忘提醒");
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
        scheduleReminder(selected);
        persist();
        reminderTable.refresh();
        info("已恢复提醒: " + selected.getContent());
    }

    /**
     * 为指定提醒排队下一次弹窗任务。
     *
     * @param reminder 要调度的提醒
     */
    private void scheduleReminder(MemoReminder reminder) {
        if (!reminder.isActive() || reminder.getRemainingTimes() == 0) {
            return;
        }
        cancelTask(reminder.getId());

        long delay = Math.max(1000L, reminder.getNextTriggerEpochMillis() - System.currentTimeMillis());
        ScheduledFuture<?> future = scheduler.schedule(() -> showReminderDialog(reminder), delay, TimeUnit.MILLISECONDS);
        scheduledTasks.put(reminder.getId(), future);
    }

    /**
     * 显示备忘提醒弹窗。
     *
     * @param reminder 要显示的提醒
     */
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

    /**
     * 根据弹窗按钮和勾选状态更新提醒状态。
     *
     * @param reminder 当前提醒
     * @param buttonType 用户点击的按钮
     * @param checkedDone 是否勾选已处理
     */
    private void handleDialogResult(MemoReminder reminder, ButtonType buttonType, boolean checkedDone) {
        if (buttonType.getButtonData() == ButtonBar.ButtonData.OTHER) {
            reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + SNOOZE_MILLIS);
            scheduleReminder(reminder);
            info("提醒已稍后5分钟: " + reminder.getContent());
            persist();
            reminderTable.refresh();
            return;
        }

        if (checkedDone) {
            if (reminder.getRemainingTimes() > 0) {
                reminder.setRemainingTimes(reminder.getRemainingTimes() - 1);
            }
            if (reminder.getRemainingTimes() == 0) {
                reminder.setActive(false);
                cancelTask(reminder.getId());
                info("提醒已完成: " + reminder.getContent());
            } else {
                reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + reminder.intervalMillis());
                scheduleReminder(reminder);
                info("提醒确认，已进入下个周期: " + reminder.getContent());
            }
        } else {
            reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + SNOOZE_MILLIS);
            scheduleReminder(reminder);
            info("未勾选处理，已自动稍后5分钟提醒: " + reminder.getContent());
        }
        persist();
        reminderTable.refresh();
    }

    /**
     * 取消指定提醒的排队任务。
     *
     * @param reminderId 提醒 ID
     */
    private void cancelTask(long reminderId) {
        ScheduledFuture<?> task = scheduledTasks.remove(reminderId);
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 从本地文件加载备忘提醒。
     */
    private void loadFromDisk() {
        try {
            if (!Files.exists(DATA_FILE)) {
                return;
            }
            String json = Files.readString(DATA_FILE, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<MemoReminder>>() {}.getType();
            List<MemoReminder> loaded = gson.fromJson(json, listType);
            if (loaded == null) {
                return;
            }
            reminders.setAll(loaded);
            long maxId = reminders.stream().mapToLong(MemoReminder::getId).max().orElse(System.currentTimeMillis());
            idGenerator.set(maxId);
            info("已加载备忘提醒数量: " + reminders.size());
        } catch (Exception e) {
            error("加载备忘提醒失败: " + e.getMessage());
        }
    }

    /**
     * 保存备忘提醒到本地文件。
     */
    private void persist() {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            String json = gson.toJson(new ArrayList<>(reminders));
            Files.writeString(DATA_FILE, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            error("保存备忘提醒失败: " + e.getMessage());
        }
    }

    /**
     * 格式化时间戳为界面展示文本。
     *
     * @param epochMillis 时间戳毫秒
     * @return 格式化后的时间文本
     */
    private String formatTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
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
                plugin.javafxtools.util.TimeUtils.getCurrentDateTime(), level, message);
        Platform.runLater(() -> {
            if (logArea != null && logArea.getScene() != null) {
                trimLogs();
                logArea.appendText(formatted + "\n");
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    /**
     * 裁剪过长的日志内容。
     */
    private void trimLogs() {
        int lineCount = logArea.getParagraphs().size();
        if (lineCount < MAX_LOG_LINES) {
            return;
        }
        String text = logArea.getText();
        int toRemove = Math.max(80, lineCount - MAX_LOG_LINES + 1);
        int idx = 0;
        while (toRemove > 0) {
            int next = text.indexOf('\n', idx);
            if (next < 0) {
                break;
            }
            idx = next + 1;
            toRemove--;
        }
        if (idx > 0) {
            logArea.deleteText(0, idx);
        }
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
        scheduledTasks.values().forEach(task -> task.cancel(false));
        scheduledTasks.clear();
        scheduler.shutdownNow();
    }
}
