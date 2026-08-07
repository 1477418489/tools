package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService.PowerDiagnostics;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService.PowerStateCapabilities;
import plugin.javafxtools.service.WindowsPowerDiagnosticsService.WakeTimerStatus;
import plugin.javafxtools.service.WindowsPowerSchedulerService;
import plugin.javafxtools.service.WindowsPowerSchedulerService.PowerAction;
import plugin.javafxtools.service.WindowsPowerSchedulerService.ScheduledTaskStatus;
import plugin.javafxtools.util.FxTheme;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Controller for persistent Windows power and wake schedules.
 */
public class WindowsPowerController extends BaseController {
    private static final DateTimeFormatter TIME_INPUT_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DISPLAY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private Label platformStatusLabel;
    @FXML private ComboBox<PowerAction> powerActionComboBox;
    @FXML private DatePicker powerDatePicker;
    @FXML private TextField powerTimeField;
    @FXML private Button schedulePowerButton;
    @FXML private Button cancelPowerButton;
    @FXML private Label powerStatusLabel;
    @FXML private Label powerDetailLabel;
    @FXML private DatePicker wakeDatePicker;
    @FXML private TextField wakeTimeField;
    @FXML private Button scheduleWakeButton;
    @FXML private Button cancelWakeButton;
    @FXML private Label wakeStatusLabel;
    @FXML private Label wakeDetailLabel;
    @FXML private Button refreshButton;
    @FXML private Label diagnosticsStatusLabel;
    @FXML private Button diagnosticsRefreshButton;
    @FXML private Label systemValueLabel;
    @FXML private Label boardValueLabel;
    @FXML private Label biosValueLabel;
    @FXML private Label firmwareValueLabel;
    @FXML private Label s3StatusLabel;
    @FXML private Label modernStandbyStatusLabel;
    @FXML private Label hibernateStatusLabel;
    @FXML private Label fastStartupStatusLabel;
    @FXML private Label wakeDeviceCountLabel;
    @FXML private Label wakeDevicesValueLabel;
    @FXML private Label wakeTimerStatusLabel;
    @FXML private Label biosInterfaceStatusLabel;
    @FXML private Label biosInterfaceDetailLabel;
    @FXML private Label acRecoveryStatusLabel;
    @FXML private Label rtcBootStatusLabel;

    private final WindowsPowerSchedulerService schedulerService =
            new WindowsPowerSchedulerService();
    private final WindowsPowerDiagnosticsService diagnosticsService =
            new WindowsPowerDiagnosticsService();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WindowsPowerScheduler");
        thread.setDaemon(true);
        return thread;
    });

    private boolean powerTaskExists;
    private boolean wakeTaskExists;
    private boolean busy;
    private boolean diagnosticsBusy;
    private boolean cleaned;

    @FXML
    public void initialize() {
        powerActionComboBox.getItems().setAll(PowerAction.values());
        powerActionComboBox.getSelectionModel().select(PowerAction.SHUTDOWN);

        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        setDateTime(powerDatePicker, powerTimeField, now.plusMinutes(10));
        setDateTime(wakeDatePicker, wakeTimeField, now.plusHours(1));
        configureDatePicker(powerDatePicker);
        configureDatePicker(wakeDatePicker);

        if (!schedulerService.isSupported()) {
            setPlatformStatus("ERROR", "仅支持 Windows");
            powerDetailLabel.setText("当前系统无法创建 Windows 电源计划");
            wakeDetailLabel.setText("当前系统无法创建 Windows 唤醒计划");
            setDiagnosticsUnavailable("当前系统不支持 Windows 电源能力检测");
            updateControlState();
            return;
        }

        setPlatformStatus("READY", "正在读取状态");
        refreshSchedules();
        refreshDiagnostics();
    }

    @FXML
    private void handleSchedulePower() {
        LocalDateTime scheduledFor = parseSchedule(powerDatePicker, powerTimeField);
        if (scheduledFor == null) {
            return;
        }
        PowerAction action = powerActionComboBox.getValue();
        if (action == null) {
            showError("无法创建计划", "请选择要执行的电源操作");
            return;
        }
        if (!confirmPowerSchedule(action, scheduledFor)) {
            return;
        }
        executeOperation(
                () -> schedulerService.schedulePowerAction(scheduledFor, action),
                action.displayName() + "计划已创建");
    }

    @FXML
    private void handleCancelPower() {
        executeOperation(schedulerService::cancelPowerAction, "电源计划已取消");
    }

    @FXML
    private void handleScheduleWake() {
        LocalDateTime scheduledFor = parseSchedule(wakeDatePicker, wakeTimeField);
        if (scheduledFor == null) {
            return;
        }
        executeOperation(() -> schedulerService.scheduleWake(scheduledFor),
                "唤醒计划已创建");
    }

    @FXML
    private void handleCancelWake() {
        executeOperation(schedulerService::cancelWake, "唤醒计划已取消");
    }

    @FXML
    private void handleRefresh() {
        refreshSchedules();
    }

    @FXML
    private void handleRefreshDiagnostics() {
        refreshDiagnostics();
    }

    private void refreshSchedules() {
        executeOperation(() -> { }, null);
    }

    private void refreshDiagnostics() {
        if (diagnosticsBusy || cleaned || !diagnosticsService.isSupported()) {
            return;
        }
        diagnosticsBusy = true;
        setTaskStatus(diagnosticsStatusLabel, "BUSY", "检测中");
        updateControlState();
        try {
            commandExecutor.execute(() -> {
                try {
                    PowerDiagnostics diagnostics = diagnosticsService.detect();
                    Platform.runLater(() -> finishDiagnostics(diagnostics));
                } catch (IOException | RuntimeException e) {
                    Platform.runLater(() -> failDiagnostics(e));
                }
            });
        } catch (RejectedExecutionException e) {
            failDiagnostics(new IOException("电源检测服务正在关闭", e));
        }
    }

    private void executeOperation(IoOperation operation, String successMessage) {
        if (busy || cleaned || !schedulerService.isSupported()) {
            return;
        }
        busy = true;
        setPlatformStatus("BUSY", "处理中");
        updateControlState();
        try {
            commandExecutor.execute(() -> {
                try {
                    operation.run();
                    ScheduleSnapshot snapshot = querySnapshot();
                    Platform.runLater(() -> finishOperation(snapshot, successMessage));
                } catch (IOException | RuntimeException e) {
                    Platform.runLater(() -> failOperation(e, successMessage != null));
                }
            });
        } catch (RejectedExecutionException e) {
            failOperation(new IOException("电源计划服务正在关闭", e), true);
        }
    }

    private ScheduleSnapshot querySnapshot() throws IOException {
        return new ScheduleSnapshot(
                schedulerService.queryPowerTask(),
                schedulerService.queryWakeTask());
    }

    private void finishOperation(ScheduleSnapshot snapshot, String successMessage) {
        if (cleaned) {
            return;
        }
        busy = false;
        updatePowerStatus(snapshot.powerTask());
        updateWakeStatus(snapshot.wakeTask());
        setPlatformStatus("SUCCESS", successMessage == null ? "状态已更新" : successMessage);
        updateControlState();
    }

    private void failOperation(Exception exception, boolean showAlert) {
        if (cleaned) {
            return;
        }
        busy = false;
        setPlatformStatus("ERROR", showAlert ? "操作失败" : "无法读取状态");
        if (!showAlert) {
            String details = conciseErrorMessage(exception);
            powerTaskExists = false;
            wakeTaskExists = false;
            setTaskStatus(powerStatusLabel, "ERROR", "无法读取");
            setTaskStatus(wakeStatusLabel, "ERROR", "无法读取");
            powerDetailLabel.setText(details);
            wakeDetailLabel.setText(details);
        }
        updateControlState();
        if (showAlert) {
            showError("Windows 电源计划操作失败", errorMessage(exception));
        }
    }

    private void finishDiagnostics(PowerDiagnostics diagnostics) {
        if (cleaned) {
            return;
        }
        diagnosticsBusy = false;
        systemValueLabel.setText(joinValues(
                diagnostics.systemManufacturer(), diagnostics.systemModel()));
        boardValueLabel.setText(joinValues(
                diagnostics.boardManufacturer(), diagnostics.boardProduct()));
        biosValueLabel.setText(joinValues(
                diagnostics.biosManufacturer(), diagnostics.biosVersion(),
                diagnostics.biosReleaseDate()));
        firmwareValueLabel.setText(diagnostics.firmwareType().displayName());

        updatePowerCapabilities(diagnostics.powerStates());
        updateWakeCapabilities(diagnostics);
        updateBiosCapabilities(diagnostics);
        setTaskStatus(diagnosticsStatusLabel, "SUCCESS", "检测完成");
        updateControlState();
    }

    private void updatePowerCapabilities(PowerStateCapabilities capabilities) {
        setCapabilityStatus(s3StatusLabel, capabilities.s3Supported(), "可用", "不可用");
        setCapabilityStatus(modernStandbyStatusLabel,
                capabilities.modernStandbySupported(), "可用", "不可用");

        if (capabilities.hibernationAvailable()) {
            setTaskStatus(hibernateStatusLabel, "SUCCESS", "可用");
        } else if (capabilities.hibernationConfigured()) {
            setTaskStatus(hibernateStatusLabel, "BUSY", "已启用但不可用");
        } else {
            setTaskStatus(hibernateStatusLabel, "OFFLINE", "未启用");
        }
        setCapabilityStatus(fastStartupStatusLabel,
                capabilities.fastStartupAvailable(), "可用", "不可用");
    }

    private void updateWakeCapabilities(PowerDiagnostics diagnostics) {
        int deviceCount = diagnostics.wakeArmedDevices().size();
        setTaskStatus(wakeDeviceCountLabel, deviceCount > 0 ? "SUCCESS" : "OFFLINE",
                deviceCount + " 个设备");
        if (deviceCount == 0) {
            wakeDevicesValueLabel.setText("没有设备被允许唤醒系统");
            wakeDevicesValueLabel.setTooltip(null);
        } else {
            String allDevices = String.join("、", diagnostics.wakeArmedDevices());
            wakeDevicesValueLabel.setText(summarizeDevices(diagnostics.wakeArmedDevices()));
            wakeDevicesValueLabel.setTooltip(new Tooltip(allDevices));
        }

        WakeTimerStatus status = diagnostics.wakeTimerStatus();
        switch (status) {
            case ACTIVE -> setTaskStatus(wakeTimerStatusLabel, "SUCCESS", "存在活动计时器");
            case NONE -> setTaskStatus(wakeTimerStatusLabel, "OFFLINE", "无活动计时器");
            case ADMIN_REQUIRED -> setTaskStatus(
                    wakeTimerStatusLabel, "BUSY", "需要管理员权限");
            case UNAVAILABLE -> setTaskStatus(wakeTimerStatusLabel, "ERROR", "无法读取");
        }
    }

    private void updateBiosCapabilities(PowerDiagnostics diagnostics) {
        if (diagnostics.vendorBiosInterfaceDetected()) {
            setTaskStatus(biosInterfaceStatusLabel, "SUCCESS", "发现厂商接口");
            biosInterfaceDetailLabel.setText(String.join("、",
                    diagnostics.biosManagementInterfaces()));
        } else {
            setTaskStatus(biosInterfaceStatusLabel, "OFFLINE", "未暴露管理接口");
            biosInterfaceDetailLabel.setText("未发现厂商 BIOS 管理接口；当前页面仅执行只读检测");
        }
        setTaskStatus(acRecoveryStatusLabel, "BUSY", "需在固件中确认");
        setTaskStatus(rtcBootStatusLabel, "BUSY", "需在固件中确认");
    }

    private void failDiagnostics(Exception exception) {
        if (cleaned) {
            return;
        }
        diagnosticsBusy = false;
        setDiagnosticsUnavailable(conciseErrorMessage(exception));
        updateControlState();
    }

    private void setDiagnosticsUnavailable(String details) {
        setTaskStatus(diagnosticsStatusLabel, "ERROR", "检测失败");
        systemValueLabel.setText(details);
        boardValueLabel.setText("无法读取");
        biosValueLabel.setText("无法读取");
        firmwareValueLabel.setText("未知");
        setTaskStatus(s3StatusLabel, "ERROR", "未知");
        setTaskStatus(modernStandbyStatusLabel, "ERROR", "未知");
        setTaskStatus(hibernateStatusLabel, "ERROR", "未知");
        setTaskStatus(fastStartupStatusLabel, "ERROR", "未知");
        setTaskStatus(wakeDeviceCountLabel, "ERROR", "未知");
        wakeDevicesValueLabel.setText("无法读取唤醒设备");
        wakeDevicesValueLabel.setTooltip(null);
        setTaskStatus(wakeTimerStatusLabel, "ERROR", "未知");
        setTaskStatus(biosInterfaceStatusLabel, "ERROR", "未知");
        biosInterfaceDetailLabel.setText("无法检测厂商 BIOS 管理接口");
    }

    private void setCapabilityStatus(Label label,
                                     boolean available,
                                     String availableText,
                                     String unavailableText) {
        setTaskStatus(label, available ? "SUCCESS" : "OFFLINE",
                available ? availableText : unavailableText);
    }

    private String summarizeDevices(java.util.List<String> devices) {
        int visibleCount = Math.min(devices.size(), 4);
        String summary = String.join("、", devices.subList(0, visibleCount));
        return devices.size() > visibleCount
                ? summary + " 等 " + devices.size() + " 个设备" : summary;
    }

    private String joinValues(String... values) {
        String result = java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .collect(java.util.stream.Collectors.joining(" · "));
        return result.isBlank() ? "未提供" : result;
    }

    private void updatePowerStatus(ScheduledTaskStatus status) {
        powerTaskExists = status.exists();
        if (!status.exists()) {
            setTaskStatus(powerStatusLabel, "OFFLINE", "未计划");
            powerDetailLabel.setText("当前没有待执行的关机、重启或休眠任务");
            return;
        }

        boolean expired = isExpired(status);
        setTaskStatus(powerStatusLabel, expired ? "BUSY" : "SUCCESS",
                expired ? "任务已到期" : "等待执行");
        String action = status.powerAction() == null
                ? "电源操作" : status.powerAction().displayName();
        powerDetailLabel.setText(action + " · " + formatTaskTime(status)
                + formatSchedulerState(status.schedulerState()));
    }

    private void updateWakeStatus(ScheduledTaskStatus status) {
        wakeTaskExists = status.exists();
        if (!status.exists()) {
            setTaskStatus(wakeStatusLabel, "OFFLINE", "未计划");
            wakeDetailLabel.setText("当前没有待执行的唤醒任务");
            return;
        }

        boolean expired = isExpired(status);
        setTaskStatus(wakeStatusLabel, expired ? "BUSY" : "SUCCESS",
                expired ? "任务已到期" : "等待唤醒");
        wakeDetailLabel.setText(formatTaskTime(status)
                + formatSchedulerState(status.schedulerState()));
    }

    private boolean isExpired(ScheduledTaskStatus status) {
        return status.nextRunTime() == null
                || !status.nextRunTime().isAfter(LocalDateTime.now());
    }

    private String formatTaskTime(ScheduledTaskStatus status) {
        return status.nextRunTime() == null
                ? "无下次执行时间"
                : DISPLAY_TIME_FORMAT.format(status.nextRunTime());
    }

    private String formatSchedulerState(String state) {
        if (state == null || state.isBlank()) {
            return "";
        }
        return " · " + switch (state.toLowerCase(Locale.ROOT)) {
            case "3", "ready" -> "就绪";
            case "4", "running" -> "执行中";
            case "1", "disabled" -> "已禁用";
            case "2", "queued" -> "已排队";
            case "0", "unknown" -> "状态未知";
            default -> state;
        };
    }

    private LocalDateTime parseSchedule(DatePicker datePicker, TextField timeField) {
        LocalDate date = datePicker.getValue();
        if (date == null) {
            showError("时间无效", "请选择计划日期");
            return null;
        }
        LocalTime time;
        try {
            time = LocalTime.parse(timeField.getText().trim(), TIME_INPUT_FORMAT);
        } catch (DateTimeParseException | NullPointerException e) {
            showError("时间无效", "时间格式应为 HH:mm，例如 23:30");
            return null;
        }

        LocalDateTime scheduledFor = LocalDateTime.of(date, time);
        if (!scheduledFor.isAfter(LocalDateTime.now().plusSeconds(30))) {
            showError("时间无效", "计划时间至少需要晚于当前时间 30 秒");
            return null;
        }
        return scheduledFor;
    }

    private boolean confirmPowerSchedule(PowerAction action,
                                         LocalDateTime scheduledFor) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "将在 " + DISPLAY_TIME_FORMAT.format(scheduledFor)
                        + " 执行“" + action.displayName() + "”。是否继续？",
                ButtonType.OK, ButtonType.CANCEL);
        FxTheme.apply(alert);
        alert.setTitle("确认电源计划");
        alert.setHeaderText(action == PowerAction.HIBERNATE
                ? "休眠前请保存正在编辑的内容"
                : "关机或重启可能导致未保存内容丢失");
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void configureDatePicker(DatePicker datePicker) {
        datePicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isBefore(LocalDate.now()));
            }
        });
    }

    private void setDateTime(DatePicker datePicker,
                             TextField timeField,
                             LocalDateTime dateTime) {
        datePicker.setValue(dateTime.toLocalDate());
        timeField.setText(TIME_INPUT_FORMAT.format(dateTime.toLocalTime()));
    }

    private void updateControlState() {
        boolean unavailable = busy || !schedulerService.isSupported() || cleaned;
        powerActionComboBox.setDisable(unavailable);
        powerDatePicker.setDisable(unavailable);
        powerTimeField.setDisable(unavailable);
        wakeDatePicker.setDisable(unavailable);
        wakeTimeField.setDisable(unavailable);
        schedulePowerButton.setDisable(unavailable);
        scheduleWakeButton.setDisable(unavailable);
        cancelPowerButton.setDisable(unavailable || !powerTaskExists);
        cancelWakeButton.setDisable(unavailable || !wakeTaskExists);
        refreshButton.setDisable(unavailable);
        diagnosticsRefreshButton.setDisable(
                diagnosticsBusy || !diagnosticsService.isSupported() || cleaned);
    }

    private void setPlatformStatus(String state, String message) {
        setTaskStatus(platformStatusLabel, state, message);
    }

    private void setTaskStatus(Label label, String state, String message) {
        label.setText(message);
        label.getStyleClass().removeAll(
                "status-offline", "status-busy", "status-online", "status-error");
        label.getStyleClass().add(switch (state) {
            case "SUCCESS" -> "status-online";
            case "BUSY" -> "status-busy";
            case "ERROR" -> "status-error";
            default -> "status-offline";
        });
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        FxTheme.apply(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private String conciseErrorMessage(Exception exception) {
        String message = errorMessage(exception).lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("Windows 任务计划程序不可用");
        return message.length() <= 180 ? message : message.substring(0, 177) + "...";
    }

    @Override
    public TextArea getLogArea() {
        return null;
    }

    @Override
    public void cleanup() {
        cleaned = true;
        commandExecutor.shutdownNow();
        super.cleanup();
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }

    private record ScheduleSnapshot(ScheduledTaskStatus powerTask,
                                    ScheduledTaskStatus wakeTask) {
    }
}
