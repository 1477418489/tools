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
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.MemoReminder;
import plugin.javafxtools.util.TimeUtils;

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

public class MemoReminderController implements ModuleLogger {
    private static final int MAX_LOG_LINES = 400;
    private static final long SNOOZE_MILLIS = 5 * 60_000L;
    private static final Path DATA_FILE = Path.of("userData", "memo_reminders.json");

    private final ObservableList<MemoReminder> reminders = FXCollections.observableArrayList();
    private final ConcurrentMap<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "memo-reminder-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @FXML
    private TextArea memoInput;
    @FXML
    private TextField intervalField;
    @FXML
    private ComboBox<MemoReminder.TimeUnit> intervalUnitBox;
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

    @FXML
    public void initialize() {
        intervalUnitBox.setItems(FXCollections.observableArrayList(MemoReminder.TimeUnit.values()));
        intervalUnitBox.setValue(MemoReminder.TimeUnit.MINUTES);
        timesField.setText("1");
        initTable();
        loadFromDisk();
        reminders.forEach(this::scheduleReminder);
        info("备忘提醒模块初始化完成");
    }

    private void initTable() {
        reminderTable.setItems(reminders);
        contentCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getContent()));
        intervalCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getDisplayInterval()));
        remainCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getDisplayRemaining()));
        nextTimeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(formatTime(data.getValue().getNextTriggerEpochMillis())));
        statusCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().isActive() ? "运行中" : "已暂停"));
    }

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

    private void scheduleReminder(MemoReminder reminder) {
        if (!reminder.isActive() || reminder.getRemainingTimes() == 0) {
            return;
        }
        cancelTask(reminder.getId());

        long delay = Math.max(1000L, reminder.getNextTriggerEpochMillis() - System.currentTimeMillis());
        ScheduledFuture<?> future = scheduler.schedule(() -> showReminderDialog(reminder), delay, TimeUnit.MILLISECONDS);
        scheduledTasks.put(reminder.getId(), future);
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

    private void cancelTask(long reminderId) {
        ScheduledFuture<?> task = scheduledTasks.remove(reminderId);
        if (task != null) {
            task.cancel(false);
        }
    }

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

    private void persist() {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            String json = gson.toJson(new ArrayList<>(reminders));
            Files.writeString(DATA_FILE, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            error("保存备忘提醒失败: " + e.getMessage());
        }
    }

    private String formatTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    @Override
    public void log(String level, String message) {
        String formatted = String.format("[%s][%s][备忘提醒] %s", TimeUtils.getCurrentDateTime(), level, message);
        Platform.runLater(() -> {
            if (logArea != null && logArea.getScene() != null) {
                trimLogs();
                logArea.appendText(formatted + "\n");
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

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

    @Override
    public TextArea getLogArea() {
        return logArea;
    }

    public void cleanup() {
        scheduledTasks.values().forEach(task -> task.cancel(false));
        scheduledTasks.clear();
        scheduler.shutdownNow();
    }
}
