package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.control.LogViewer;
import plugin.javafxtools.model.IntervalUnit;
import plugin.javafxtools.model.MemoReminder;
import plugin.javafxtools.model.ReminderScheduleMode;
import plugin.javafxtools.service.MemoReminderSchedulerService;
import plugin.javafxtools.service.MemoReminderStore;
import plugin.javafxtools.service.MemoReminderTableSupport;
import plugin.javafxtools.util.FxTheme;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.function.BooleanSupplier;

/**
 * 备忘提醒页签控制器，负责 FXML 事件入口、表单校验和服务装配。
 */
public class MemoReminderController extends BaseController {
    private static final DateTimeFormatter TIME_INPUT_FORMATTER = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalEnd()
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

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
    private ToggleButton intervalModeButton;

    @FXML
    private ToggleButton atTimeModeButton;

    @FXML
    private HBox intervalOptions;

    @FXML
    private HBox atTimeOptions;

    @FXML
    private DatePicker reminderDatePicker;

    @FXML
    private TextField reminderTimeField;

    @FXML
    private TableView<MemoReminder> reminderTable;

    @FXML
    private TableColumn<MemoReminder, String> contentCol;

    @FXML
    private TableColumn<MemoReminder, String> scheduleCol;

    @FXML
    private TableColumn<MemoReminder, String> remainCol;

    @FXML
    private TableColumn<MemoReminder, String> nextTimeCol;

    @FXML
    private TableColumn<MemoReminder, String> statusCol;

    private TextArea logArea;

    @FXML
    private LogViewer logViewer;

    @FXML
    private Button addButton;

    @FXML
    private Button pauseButton;

    @FXML
    private Button resumeButton;

    @FXML
    private Button removeButton;

    @FXML
    private Label reminderCountLabel;

    private MemoReminderSchedulerService schedulerService;
    private final ToggleGroup scheduleModeGroup = new ToggleGroup();

    /**
     * 初始化备忘提醒页签。
     */
    @FXML
    public void initialize() {
        logArea = logViewer.getTextArea();
        logViewer.setOnClear(this::handleClearLog);
        intervalField.setTextFormatter(unsignedIntegerFormatter());
        timesField.setTextFormatter(signedIntegerFormatter());
        reminderTimeField.setTextFormatter(timeFormatter());
        intervalUnitBox.setItems(FXCollections.observableArrayList(IntervalUnit.values()));
        intervalUnitBox.setValue(IntervalUnit.MINUTES);
        timesField.setText("1");
        initializeScheduleModeControls();
        initializeDateTimeControls();
        new MemoReminderTableSupport(reminderTable, contentCol, scheduleCol,
                remainCol, nextTimeCol, statusCol, reminders).initialize();
        schedulerService = new MemoReminderSchedulerService(
                reminderTable, this::persist, this::updateUiState, this::info);
        loadFromDisk();
        reminders.forEach(schedulerService::scheduleReminder);
        reminderTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateActionButtons());
        reminders.addListener((javafx.collections.ListChangeListener<MemoReminder>) change -> updateUiState());
        memoInput.textProperty().addListener((observable, oldValue, newValue) -> updateActionButtons());
        intervalField.textProperty().addListener((observable, oldValue, newValue) -> updateActionButtons());
        timesField.textProperty().addListener((observable, oldValue, newValue) -> updateActionButtons());
        reminderDatePicker.valueProperty().addListener(
                (observable, oldValue, newValue) -> updateActionButtons());
        reminderTimeField.textProperty().addListener(
                (observable, oldValue, newValue) -> updateActionButtons());
        updateUiState();
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

        MemoReminder reminder = createReminder(content);
        if (reminder == null) {
            return;
        }
        List<MemoReminder> updatedReminders = new ArrayList<>(reminders);
        updatedReminders.add(reminder);
        if (!persist(updatedReminders)) {
            return;
        }
        reminders.add(reminder);
        schedulerService.scheduleReminder(reminder);
        info((reminder.getScheduleMode() == ReminderScheduleMode.AT_TIME
                ? "新增定时闹钟: " : "新增周期提醒: ") + content);
        memoInput.clear();
        memoInput.requestFocus();
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
        if (!confirmRemoval(selected)) {
            return;
        }
        List<MemoReminder> updatedReminders = new ArrayList<>(reminders);
        updatedReminders.remove(selected);
        if (!persist(updatedReminders)) {
            return;
        }
        schedulerService.cancelTask(selected.getId());
        reminders.remove(selected);
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
        if (!persist()) {
            selected.setActive(true);
            reminderTable.refresh();
            updateActionButtons();
            return;
        }
        schedulerService.cancelTask(selected.getId());
        reminderTable.refresh();
        info("已暂停提醒: " + selected.getContent());
        updateUiState();
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
        boolean previousActive = selected.isActive();
        long previousNextTrigger = selected.getNextTriggerEpochMillis();
        selected.setActive(true);
        if (selected.getNextTriggerEpochMillis() <= 0
                && selected.getScheduleMode() == ReminderScheduleMode.INTERVAL) {
            selected.setNextTriggerEpochMillis(System.currentTimeMillis() + selected.intervalMillis());
        }
        if (!persist()) {
            selected.setActive(previousActive);
            selected.setNextTriggerEpochMillis(previousNextTrigger);
            reminderTable.refresh();
            updateActionButtons();
            return;
        }
        schedulerService.scheduleReminder(selected);
        reminderTable.refresh();
        info("已恢复提醒: " + selected.getContent());
        updateUiState();
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
    @Override
    public void cleanup() {
        if (schedulerService != null) {
            schedulerService.cleanup();
        }
        super.cleanup();
    }

    public void setReminderSoundEnabledSupplier(BooleanSupplier supplier) {
        if (schedulerService != null) {
            schedulerService.setSoundEnabledSupplier(supplier);
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

    private boolean persist() {
        return persist(new ArrayList<>(reminders));
    }

    private boolean persist(List<MemoReminder> updatedReminders) {
        try {
            reminderStore.save(updatedReminders);
            return true;
        } catch (IOException e) {
            error("保存备忘提醒失败: " + e.getMessage());
            return false;
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
            error("提醒间隔请输入有效整数");
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

    private void updateUiState() {
        long activeCount = reminders.stream().filter(MemoReminder::isActive).count();
        reminderCountLabel.setText(activeCount + " 进行中 · " + reminders.size() + " 条");
        reminderCountLabel.getStyleClass().removeAll("status-online", "status-offline");
        reminderCountLabel.getStyleClass().add(activeCount > 0 ? "status-online" : "status-offline");
        updateActionButtons();
    }

    private void updateActionButtons() {
        MemoReminder selected = reminderTable.getSelectionModel().getSelectedItem();
        boolean hasSelection = selected != null;
        boolean validContent = memoInput.getText() != null && !memoInput.getText().isBlank();
        boolean validSchedule;
        if (selectedScheduleMode() == ReminderScheduleMode.AT_TIME) {
            Long triggerTime = parseTriggerEpochMillis(false);
            validSchedule = triggerTime != null && triggerTime > System.currentTimeMillis();
        } else {
            Integer interval = parseNumber(intervalField.getText());
            Integer times = parseNumber(timesField.getText());
            validSchedule = interval != null && interval > 0 && times != null;
        }

        addButton.setDisable(!validContent || !validSchedule);
        pauseButton.setDisable(!hasSelection || !selected.isActive());
        resumeButton.setDisable(!hasSelection || selected.isActive() || selected.getRemainingTimes() == 0);
        removeButton.setDisable(!hasSelection);
    }

    private boolean confirmRemoval(MemoReminder reminder) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除提醒 \"" + reminder.getContent() + "\"？",
                ButtonType.OK, ButtonType.CANCEL);
        FxTheme.apply(alert);
        alert.setTitle("删除提醒");
        alert.setHeaderText(null);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private TextFormatter<String> unsignedIntegerFormatter() {
        UnaryOperator<TextFormatter.Change> filter = change ->
                change.getControlNewText().matches("\\d*") ? change : null;
        return new TextFormatter<>(filter);
    }

    private TextFormatter<String> signedIntegerFormatter() {
        UnaryOperator<TextFormatter.Change> filter = change ->
                change.getControlNewText().matches("-?\\d*") ? change : null;
        return new TextFormatter<>(filter);
    }

    private TextFormatter<String> timeFormatter() {
        UnaryOperator<TextFormatter.Change> filter = change ->
                change.getControlNewText().matches("[0-9:]{0,8}") ? change : null;
        return new TextFormatter<>(filter);
    }

    private void initializeScheduleModeControls() {
        intervalModeButton.setToggleGroup(scheduleModeGroup);
        atTimeModeButton.setToggleGroup(scheduleModeGroup);
        intervalModeButton.setSelected(true);
        scheduleModeGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null) {
                Platform.runLater(() -> {
                    if (scheduleModeGroup.getSelectedToggle() == null) {
                        oldValue.setSelected(true);
                    }
                });
                return;
            }
            updateScheduleModeUi();
        });
        updateScheduleModeUi();
    }

    private void initializeDateTimeControls() {
        LocalDateTime initialTime = LocalDateTime.now()
                .plusMinutes(5)
                .withSecond(0)
                .withNano(0);
        reminderDatePicker.setValue(initialTime.toLocalDate());
        reminderTimeField.setText(initialTime.toLocalTime().format(TIME_INPUT_FORMATTER));
        reminderDatePicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isBefore(LocalDate.now()));
            }
        });
    }

    private void updateScheduleModeUi() {
        boolean atTime = selectedScheduleMode() == ReminderScheduleMode.AT_TIME;
        intervalOptions.setManaged(!atTime);
        intervalOptions.setVisible(!atTime);
        atTimeOptions.setManaged(atTime);
        atTimeOptions.setVisible(atTime);
        addButton.setText(atTime ? "新增闹钟" : "新增提醒");
        updateActionButtons();
    }

    private ReminderScheduleMode selectedScheduleMode() {
        return atTimeModeButton.isSelected()
                ? ReminderScheduleMode.AT_TIME
                : ReminderScheduleMode.INTERVAL;
    }

    private MemoReminder createReminder(String content) {
        long id = idGenerator.incrementAndGet();
        if (selectedScheduleMode() == ReminderScheduleMode.AT_TIME) {
            Long triggerTime = parseTriggerEpochMillis(true);
            if (triggerTime == null) {
                return null;
            }
            if (triggerTime <= System.currentTimeMillis()) {
                error("指定提醒时间必须晚于当前时间");
                return null;
            }
            return MemoReminder.atTime(id, content, triggerTime);
        }

        Integer interval = parsePositiveNumber(intervalField.getText(), "提醒间隔必须大于 0");
        if (interval == null) {
            return null;
        }
        Integer times = parseNumber(timesField.getText());
        if (times == null) {
            error("提醒次数请输入有效整数");
            return null;
        }
        MemoReminder reminder = new MemoReminder(
                id, content, interval, intervalUnitBox.getValue(), times);
        reminder.setNextTriggerEpochMillis(System.currentTimeMillis() + reminder.intervalMillis());
        return reminder;
    }

    private Long parseTriggerEpochMillis(boolean reportError) {
        LocalDate date = reminderDatePicker.getValue();
        if (date == null) {
            if (reportError) {
                error("请选择提醒日期");
            }
            return null;
        }
        try {
            LocalTime time = LocalTime.parse(reminderTimeField.getText().trim(), TIME_INPUT_FORMATTER);
            return LocalDateTime.of(date, time)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException | NullPointerException e) {
            if (reportError) {
                error("提醒时间请输入 HH:mm 或 HH:mm:ss 格式");
            }
            return null;
        }
    }
}
