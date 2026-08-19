package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.control.LogViewer;
import plugin.javafxtools.model.LogMatchMode;
import plugin.javafxtools.model.LogMonitorAutomation;
import plugin.javafxtools.model.LogMonitorConfig;
import plugin.javafxtools.model.LogMonitorMatch;
import plugin.javafxtools.model.LogMonitorRule;
import plugin.javafxtools.model.LogRemoteMatchAction;
import plugin.javafxtools.service.LogMonitorAlertService;
import plugin.javafxtools.service.LogMonitorAutomationService;
import plugin.javafxtools.service.LogMonitorMatcher;
import plugin.javafxtools.service.LogMonitorService;
import plugin.javafxtools.service.LogMonitorStatus;
import plugin.javafxtools.service.LogMonitorStore;
import plugin.javafxtools.service.WindowsWindowPickerService;
import plugin.javafxtools.util.FxTheme;
import plugin.javafxtools.util.HttpUrlSupport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Coordinates the log-monitor form, persistence, polling, and popup alerts. */
public final class LogMonitorController extends BaseController {
    private static final int MAX_RECENT_MATCHES = 200;
    private static final DateTimeFormatter MATCH_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ObservableList<LogMonitorRule> rules = FXCollections.observableArrayList();
    private final ObservableList<LogMonitorMatch> recentMatches = FXCollections.observableArrayList();
    private final Map<String, BooleanProperty> automationRuleSelections = new HashMap<>();
    private final LogMonitorStore store = new LogMonitorStore();
    private final LogMonitorService monitorService = new LogMonitorService();
    private final LogMonitorAlertService alertService = new LogMonitorAlertService();
    private final LogMonitorAutomationService automationService =
            new LogMonitorAutomationService();
    private final WindowsWindowPickerService windowPickerService =
            new WindowsWindowPickerService();
    private final LogMonitorFxDispatcher matchDispatcher =
            new LogMonitorFxDispatcher(Platform::runLater, this::acceptMatches);
    private final LogMonitorService.Listener monitorListener = new MonitorListener();

    @FXML
    private TextField logFileField;
    @FXML
    private Button saveConfigButton;
    @FXML
    private Button startMonitorButton;
    @FXML
    private Button stopMonitorButton;
    @FXML
    private Label monitorStatusLabel;
    @FXML
    private TextField ruleNameField;
    @FXML
    private TextField ruleExpressionField;
    @FXML
    private ComboBox<LogMatchMode> matchModeComboBox;
    @FXML
    private CheckBox caseSensitiveCheckBox;
    @FXML
    private CheckBox ruleEnabledCheckBox;
    @FXML
    private Button updateRuleButton;
    @FXML
    private Button deleteRuleButton;
    @FXML
    private TableView<LogMonitorRule> ruleTable;
    @FXML
    private TableColumn<LogMonitorRule, String> ruleNameColumn;
    @FXML
    private TableColumn<LogMonitorRule, String> ruleExpressionColumn;
    @FXML
    private TableColumn<LogMonitorRule, String> ruleModeColumn;
    @FXML
    private TableColumn<LogMonitorRule, String> ruleCaseColumn;
    @FXML
    private TableColumn<LogMonitorRule, String> ruleEnabledColumn;
    @FXML
    private TableView<LogMonitorMatch> matchTable;
    @FXML
    private TableColumn<LogMonitorMatch, String> matchTimeColumn;
    @FXML
    private TableColumn<LogMonitorMatch, String> matchRuleColumn;
    @FXML
    private TableColumn<LogMonitorMatch, String> matchExpressionColumn;
    @FXML
    private TableColumn<LogMonitorMatch, String> matchLineColumn;
    @FXML
    private Label matchCountLabel;
    @FXML
    private CheckBox automationEnabledCheckBox;
    @FXML
    private ListView<LogMonitorRule> automationRuleList;
    @FXML
    private TextField automationTargetField;
    @FXML
    private Button automationTargetPickerButton;
    @FXML
    private CheckBox automationTypeTextCheckBox;
    @FXML
    private TextField automationTextField;
    @FXML
    private CheckBox automationPressEnterCheckBox;
    @FXML
    private Spinner<Integer> automationStartSpinner;
    @FXML
    private Spinner<Integer> automationEverySpinner;
    @FXML
    private Spinner<Integer> automationMaxSpinner;
    @FXML
    private CheckBox remoteCheckEnabledCheckBox;
    @FXML
    private TextField remoteUrlField;
    @FXML
    private TextField remoteKeywordField;
    @FXML
    private ComboBox<LogRemoteMatchAction> remoteMatchActionComboBox;
    @FXML
    private LogViewer logViewer;

    private TextArea logArea;
    private Stage primaryStage;
    private LogMonitorConfig activeConfig;
    private volatile boolean cleanedUp;
    private boolean windowPickerBusy;

    @FXML
    public void initialize() {
        logArea = logViewer.getTextArea();
        logViewer.setOnClear(this::handleClearLog);
        configureModeSelector();
        configureAutomationEditor();
        configureTables();
        configureSelection();
        resetRuleEditor();
        loadConfiguration();
        updateMatchCount();
        updateActionButtons();
    }

    @FXML
    private void selectLogFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要监听的日志文件");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("日志文件", "*.log", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        initialDirectory().ifPresent(chooser::setInitialDirectory);
        File selected = chooser.showOpenDialog(ownerWindow());
        if (selected != null) {
            logFileField.setText(selected.getAbsolutePath());
            info("已选择日志文件: " + selected.getAbsolutePath());
        }
    }

    @FXML
    private void saveConfig() {
        LogMonitorConfig candidate;
        try {
            candidate = createConfig(monitorService.isRunning());
        } catch (IllegalArgumentException exception) {
            reportValidationError(exception.getMessage());
            return;
        }

        LogMonitorConfig previousRunningConfig = activeConfig;
        boolean restart = monitorService.isRunning();
        boolean candidatePersisted = false;
        if (restart) {
            monitorService.stop();
            matchDispatcher.clear();
            alertService.reset();
            automationService.reset();
            activeConfig = null;
        }
        try {
            store.save(candidate);
            candidatePersisted = true;
            if (restart) {
                startService(candidate);
            }
            showStatus(restart ? "监听中" : "配置已保存",
                    restart ? LogMonitorStatus.RUNNING : LogMonitorStatus.STOPPED,
                    candidate.logFile());
            info("日志监控配置已保存");
        } catch (IOException | RuntimeException exception) {
            error("保存日志监控配置失败: " + exception.getMessage());
            showStatus("保存失败", LogMonitorStatus.ERROR, exception.getMessage());
            if (restart && previousRunningConfig != null) {
                try {
                    startService(previousRunningConfig);
                    info("已恢复保存前的监听配置");
                } catch (RuntimeException restoreFailure) {
                    error("恢复原监听配置失败: " + restoreFailure.getMessage());
                    if (candidatePersisted) {
                        persistDisabledAfterFailedStart(candidate);
                    }
                    updateActionButtons();
                    return;
                }
                if (candidatePersisted) {
                    try {
                        store.save(previousRunningConfig);
                    } catch (IOException rollbackFailure) {
                        error("监听已恢复，但无法回滚磁盘配置: "
                                + rollbackFailure.getMessage());
                    }
                }
            }
        }
        updateActionButtons();
    }

    @FXML
    private void startMonitoring() {
        if (monitorService.isRunning()) {
            return;
        }
        LogMonitorConfig candidate;
        try {
            candidate = createConfig(true);
        } catch (IllegalArgumentException exception) {
            reportValidationError(exception.getMessage());
            return;
        }

        boolean persisted = false;
        try {
            store.save(candidate);
            persisted = true;
            startService(candidate);
            showStatus("正在启动", LogMonitorStatus.WAITING_FOR_FILE, candidate.logFile());
            info("已启动日志监听: " + candidate.logFile());
        } catch (IOException | RuntimeException exception) {
            if (persisted && !monitorService.isRunning()) {
                persistDisabledAfterFailedStart(candidate);
            }
            error("启动日志监听失败: " + exception.getMessage());
            showStatus("启动失败", LogMonitorStatus.ERROR, exception.getMessage());
        }
        updateActionButtons();
    }

    @FXML
    private void stopMonitoring() {
        LogMonitorConfig runningConfig = activeConfig;
        monitorService.stop();
        matchDispatcher.clear();
        alertService.reset();
        automationService.reset();
        activeConfig = null;

        LogMonitorConfig stoppedConfig;
        try {
            stoppedConfig = createConfig(false);
        } catch (IllegalArgumentException exception) {
            stoppedConfig = runningConfig == null ? null : withEnabled(runningConfig, false);
            error("监听已停止，但当前编辑内容未保存: " + exception.getMessage());
        }
        if (stoppedConfig != null) {
            try {
                store.save(stoppedConfig);
            } catch (IOException exception) {
                error("监听已停止，但无法保存停用状态: " + exception.getMessage());
            }
        }
        showStatus("已停止", LogMonitorStatus.STOPPED, "");
        info("日志监听已停止");
        updateActionButtons();
    }

    @FXML
    private void addRule() {
        LogMonitorRule rule;
        try {
            rule = ruleFromEditor(UUID.randomUUID().toString());
            validateRulesWith(rule, -1);
        } catch (IllegalArgumentException exception) {
            reportValidationError(exception.getMessage());
            return;
        }
        rules.add(rule);
        ruleTable.getSelectionModel().select(rule);
        info("已新增规则: " + rule.name());
        updateActionButtons();
    }

    @FXML
    private void updateRule() {
        int selectedIndex = ruleTable.getSelectionModel().getSelectedIndex();
        if (selectedIndex < 0) {
            reportValidationError("请先选择要更新的规则");
            return;
        }
        LogMonitorRule existing = rules.get(selectedIndex);
        LogMonitorRule updated;
        try {
            updated = ruleFromEditor(existing.id());
            validateRulesWith(updated, selectedIndex);
        } catch (IllegalArgumentException exception) {
            reportValidationError(exception.getMessage());
            return;
        }
        rules.set(selectedIndex, updated);
        ruleTable.getSelectionModel().select(selectedIndex);
        info("已更新规则: " + updated.name());
        updateActionButtons();
    }

    @FXML
    private void deleteRule() {
        LogMonitorRule selected = ruleTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            reportValidationError("请先选择要删除的规则");
            return;
        }
        automationRuleSelections.remove(selected.id());
        rules.remove(selected);
        ruleTable.getSelectionModel().clearSelection();
        resetRuleEditor();
        info("已删除规则: " + selected.name());
        updateActionButtons();
    }

    public void setPrimaryStage(Stage stage) {
        primaryStage = stage;
        alertService.setOwner(stage);
    }

    public void setReminderSoundEnabledSupplier(BooleanSupplier supplier) {
        alertService.setSoundEnabledSupplier(supplier);
    }

    @Override
    public TextArea getLogArea() {
        return logArea;
    }

    @Override
    public void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        matchDispatcher.close();
        monitorService.close();
        alertService.close();
        automationService.close();
        super.cleanup();
    }

    private void configureModeSelector() {
        matchModeComboBox.setItems(FXCollections.observableArrayList(LogMatchMode.values()));
        matchModeComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(LogMatchMode mode) {
                return mode == null ? "" : modeLabel(mode);
            }

            @Override
            public LogMatchMode fromString(String value) {
                return null;
            }
        });
    }

    private void configureTables() {
        ruleTable.setItems(rules);
        ruleNameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        ruleExpressionColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(cell.getValue().expression()));
        ruleModeColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(modeLabel(cell.getValue().mode())));
        ruleCaseColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(cell.getValue().caseSensitive() ? "区分" : "忽略"));
        ruleEnabledColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(cell.getValue().enabled() ? "启用" : "停用"));

        matchTable.setItems(recentMatches);
        matchTimeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                MATCH_TIME_FORMATTER.format(cell.getValue().matchedAt())));
        matchRuleColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(cell.getValue().ruleName()));
        matchExpressionColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(cell.getValue().expression()));
        matchLineColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(cell.getValue().line()));
    }

    @FXML
    private void selectAutomationTargetWindow() {
        if (windowPickerBusy || cleanedUp) {
            return;
        }
        windowPickerBusy = true;
        updateAutomationControlState();
        info("正在读取当前可见窗口...");
        Thread.ofVirtual().name("log-monitor-window-picker").start(() -> {
            try {
                List<WindowsWindowPickerService.WindowTarget> targets =
                        windowPickerService.listVisibleWindows();
                Platform.runLater(() -> finishWindowTargetLoad(targets, null));
            } catch (IOException | RuntimeException exception) {
                Platform.runLater(() -> finishWindowTargetLoad(List.of(), exception));
            }
        });
    }

    private void finishWindowTargetLoad(
            List<WindowsWindowPickerService.WindowTarget> targets, Exception failure) {
        if (cleanedUp) {
            return;
        }
        windowPickerBusy = false;
        updateAutomationControlState();
        if (failure != null) {
            String message = failure.getMessage();
            error("读取当前窗口失败: "
                    + (message == null || message.isBlank()
                    ? failure.getClass().getSimpleName() : message));
            return;
        }
        if (targets.isEmpty()) {
            info("未找到可选择的窗口");
            return;
        }

        ChoiceDialog<WindowsWindowPickerService.WindowTarget> dialog =
                new ChoiceDialog<>(targets.getFirst(), targets);
        FxTheme.apply(dialog);
        dialog.setTitle("选择自动响应目标");
        dialog.setHeaderText("当前可见窗口");
        dialog.setContentText("目标窗口:");
        dialog.getDialogPane().setPrefWidth(720);
        Window owner = ownerWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        Optional<WindowsWindowPickerService.WindowTarget> selected = dialog.showAndWait();
        selected.ifPresent(target -> {
            automationTargetField.setText(target.selector());
            info("已选择自动响应目标: " + target);
        });
    }

    private void configureAutomationEditor() {
        automationRuleList.setItems(rules);
        automationRuleList.setCellFactory(CheckBoxListCell.forListView(
                rule -> automationRuleSelections.computeIfAbsent(rule.id(),
                        ignored -> new SimpleBooleanProperty()), new StringConverter<>() {
            @Override
            public String toString(LogMonitorRule rule) {
                return rule == null ? "" : rule.name() + " (" + rule.expression() + ")";
            }

            @Override
            public LogMonitorRule fromString(String value) {
                return null;
            }
        }));
        remoteMatchActionComboBox.setItems(
                FXCollections.observableArrayList(LogRemoteMatchAction.values()));
        remoteMatchActionComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(LogRemoteMatchAction action) {
                if (action == null) {
                    return "";
                }
                return action == LogRemoteMatchAction.CONTINUE_INPUT ? "继续输入" : "不再操作";
            }

            @Override
            public LogRemoteMatchAction fromString(String value) {
                return null;
            }
        });
        automationStartSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1_000_000, 1));
        automationEverySpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1_000_000, 1));
        automationMaxSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 1_000_000, 1));
        automationTargetField.setTooltip(new Tooltip(
                "优先填写唯一的窗口标题关键字；进程名只能定位当前活动窗口"));
        automationMaxSpinner.setTooltip(new Tooltip("0 表示不限制执行次数"));
        automationEnabledCheckBox.selectedProperty().addListener(
                (observable, oldValue, selected) -> updateAutomationControlState());
        automationTypeTextCheckBox.selectedProperty().addListener(
                (observable, oldValue, selected) -> updateAutomationControlState());
        remoteCheckEnabledCheckBox.selectedProperty().addListener(
                (observable, oldValue, selected) -> updateAutomationControlState());
        updateAutomationControlState();
    }

    private void configureSelection() {
        ruleTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> {
                    if (selected != null) {
                        ruleNameField.setText(selected.name());
                        ruleExpressionField.setText(selected.expression());
                        matchModeComboBox.setValue(selected.mode());
                        caseSensitiveCheckBox.setSelected(selected.caseSensitive());
                        ruleEnabledCheckBox.setSelected(selected.enabled());
                    }
                    updateActionButtons();
                });
    }

    private void loadConfiguration() {
        LogMonitorConfig config;
        try {
            config = store.load();
        } catch (IOException | RuntimeException exception) {
            config = LogMonitorConfig.defaults();
            String message = "配置读取失败，已使用默认配置: " + exception.getMessage();
            error(message);
            showStatus("配置读取失败", LogMonitorStatus.ERROR, message);
        }
        applyConfig(config);
        if (config.enabled()) {
            try {
                startService(config);
                showStatus("正在启动", LogMonitorStatus.WAITING_FOR_FILE, config.logFile());
                info("已恢复日志监听: " + config.logFile());
            } catch (RuntimeException exception) {
                error("自动恢复日志监听失败: " + exception.getMessage());
                showStatus("恢复失败", LogMonitorStatus.ERROR, exception.getMessage());
            }
        } else {
            showStatus("已停止", LogMonitorStatus.STOPPED, "");
        }
    }

    private void applyConfig(LogMonitorConfig config) {
        logFileField.setText(config.logFile());
        rules.setAll(config.rules());
        applyAutomation(config.automation());
    }

    private LogMonitorConfig createConfig(boolean enabled) {
        String rawPath = logFileField.getText() == null ? "" : logFileField.getText().trim();
        if (rawPath.isEmpty()) {
            throw new IllegalArgumentException("日志文件路径不能为空");
        }
        Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("日志文件路径无效: " + exception.getMessage(), exception);
        }
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("日志路径不是普通文件: " + path);
        }

        List<LogMonitorRule> snapshot = List.copyOf(rules);
        for (LogMonitorRule rule : snapshot) {
            if (rule.id() == null || rule.id().isBlank()
                    || rule.name() == null || rule.name().isBlank()) {
                throw new IllegalArgumentException("规则名称和标识不能为空");
            }
        }
        try {
            new LogMonitorMatcher(snapshot);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("规则校验失败: " + exception.getMessage(), exception);
        }
        LogMonitorAutomation automation = automationFromEditor(snapshot);
        return new LogMonitorConfig(enabled, path.toString(), snapshot, automation);
    }

    private LogMonitorAutomation automationFromEditor(List<LogMonitorRule> snapshot) {
        List<String> triggerRuleIds = snapshot.stream()
                .filter(rule -> isAutomationRuleSelected(rule.id()))
                .map(LogMonitorRule::id)
                .toList();
        LogRemoteMatchAction remoteAction = remoteMatchActionComboBox.getValue();
        if (remoteAction == null) {
            remoteAction = LogRemoteMatchAction.CONTINUE_INPUT;
        }
        LogMonitorAutomation automation = new LogMonitorAutomation(
                automationEnabledCheckBox.isSelected(), triggerRuleIds,
                textOf(automationTargetField).trim(), automationTypeTextCheckBox.isSelected(),
                textOf(automationTextField), automationPressEnterCheckBox.isSelected(),
                automationStartSpinner.getValue(), automationEverySpinner.getValue(),
                automationMaxSpinner.getValue(), remoteCheckEnabledCheckBox.isSelected(),
                textOf(remoteUrlField).trim(), textOf(remoteKeywordField), remoteAction);
        automation.validate(snapshot);
        if (automation.enabled() && automation.remoteCheckEnabled()) {
            HttpUrlSupport.parse(automation.remoteUrl());
        }
        return automation;
    }

    private void applyAutomation(LogMonitorAutomation automation) {
        automationEnabledCheckBox.setSelected(automation.enabled());
        automationTargetField.setText(automation.targetWindow());
        automationTypeTextCheckBox.setSelected(automation.typeText());
        automationTextField.setText(automation.text());
        automationPressEnterCheckBox.setSelected(automation.pressEnter());
        automationStartSpinner.getValueFactory().setValue(automation.startAtMatch());
        automationEverySpinner.getValueFactory().setValue(automation.everyMatches());
        automationMaxSpinner.getValueFactory().setValue(automation.maxExecutions());
        remoteCheckEnabledCheckBox.setSelected(automation.remoteCheckEnabled());
        remoteUrlField.setText(automation.remoteUrl());
        remoteKeywordField.setText(automation.remoteKeyword());
        remoteMatchActionComboBox.setValue(automation.remoteMatchAction());
        automationRuleSelections.keySet().retainAll(
                rules.stream().map(LogMonitorRule::id).collect(java.util.stream.Collectors.toSet()));
        for (LogMonitorRule rule : rules) {
            automationRuleSelections.computeIfAbsent(rule.id(), ignored -> new SimpleBooleanProperty())
                    .set(automation.triggerRuleIds().contains(rule.id()));
        }
        updateAutomationControlState();
    }

    private boolean isAutomationRuleSelected(String ruleId) {
        BooleanProperty selected = automationRuleSelections.get(ruleId);
        return selected != null && selected.get();
    }

    private void updateAutomationControlState() {
        boolean enabled = automationEnabledCheckBox.isSelected();
        automationRuleList.setDisable(!enabled);
        automationTargetField.setDisable(!enabled);
        automationTargetPickerButton.setDisable(!enabled || windowPickerBusy);
        automationTypeTextCheckBox.setDisable(!enabled);
        automationTextField.setDisable(!enabled || !automationTypeTextCheckBox.isSelected());
        automationPressEnterCheckBox.setDisable(!enabled);
        automationStartSpinner.setDisable(!enabled);
        automationEverySpinner.setDisable(!enabled);
        automationMaxSpinner.setDisable(!enabled);
        remoteCheckEnabledCheckBox.setDisable(!enabled);
        boolean remoteEnabled = enabled && remoteCheckEnabledCheckBox.isSelected();
        remoteUrlField.setDisable(!remoteEnabled);
        remoteKeywordField.setDisable(!remoteEnabled);
        remoteMatchActionComboBox.setDisable(!remoteEnabled);
    }

    private static String textOf(TextField field) {
        return field.getText() == null ? "" : field.getText();
    }

    private LogMonitorRule ruleFromEditor(String id) {
        String name = ruleNameField.getText() == null ? "" : ruleNameField.getText().trim();
        String expression = ruleExpressionField.getText() == null ? "" : ruleExpressionField.getText();
        LogMatchMode mode = matchModeComboBox.getValue();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        if (expression.isBlank()) {
            throw new IllegalArgumentException("匹配内容不能为空");
        }
        if (mode == null) {
            throw new IllegalArgumentException("请选择匹配方式");
        }
        return new LogMonitorRule(id, name, expression, mode,
                caseSensitiveCheckBox.isSelected(), ruleEnabledCheckBox.isSelected());
    }

    private void validateRulesWith(LogMonitorRule candidate, int replacedIndex) {
        List<LogMonitorRule> candidateRules = new ArrayList<>(rules);
        if (replacedIndex >= 0) {
            candidateRules.set(replacedIndex, candidate);
        } else {
            candidateRules.add(candidate);
        }
        try {
            new LogMonitorMatcher(candidateRules);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("规则校验失败: " + exception.getMessage(), exception);
        }
    }

    private void resetRuleEditor() {
        ruleNameField.clear();
        ruleExpressionField.clear();
        matchModeComboBox.setValue(LogMatchMode.WHOLE_TOKEN);
        caseSensitiveCheckBox.setSelected(true);
        ruleEnabledCheckBox.setSelected(true);
    }

    private void startService(LogMonitorConfig config) {
        matchDispatcher.clear();
        alertService.reset();
        automationService.configure(config.automation(), this::acceptAutomationEvent);
        try {
            monitorService.start(config, monitorListener);
        } catch (RuntimeException exception) {
            automationService.reset();
            throw exception;
        }
        activeConfig = config;
    }

    private void acceptAutomationEvent(LogMonitorAutomationService.Event event) {
        dispatchToFx(() -> {
            String message = "自动响应（第 " + event.matchCount() + " 次命中）: "
                    + event.message();
            if (event.type() == LogMonitorAutomationService.EventType.ERROR) {
                error(message);
            } else {
                info(message);
            }
        });
    }

    private void persistDisabledAfterFailedStart(LogMonitorConfig config) {
        try {
            store.save(withEnabled(config, false));
        } catch (IOException rollbackFailure) {
            error("无法回滚自动启动状态: " + rollbackFailure.getMessage());
        }
    }

    private static LogMonitorConfig withEnabled(LogMonitorConfig config, boolean enabled) {
        return new LogMonitorConfig(enabled, config.logFile(), config.rules(), config.automation());
    }

    private java.util.Optional<File> initialDirectory() {
        try {
            String text = logFileField.getText();
            if (text == null || text.isBlank()) {
                return java.util.Optional.empty();
            }
            Path parent = Path.of(text.trim()).toAbsolutePath().getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return java.util.Optional.of(parent.toFile());
            }
        } catch (InvalidPathException | SecurityException ignored) {
            // The chooser can still open from its platform default location.
        }
        return java.util.Optional.empty();
    }

    private Window ownerWindow() {
        if (primaryStage != null) {
            return primaryStage;
        }
        return logFileField.getScene() == null ? null : logFileField.getScene().getWindow();
    }

    private void acceptMatches(List<LogMonitorMatch> matches, long droppedCount) {
        if (cleanedUp) {
            return;
        }
        if (droppedCount > 0) {
            log("WARN", "高负载期间已省略 " + droppedCount
                    + " 条界面命中明细，告警计数已保留");
        }
        List<LogMonitorMatch> newestFirst = new ArrayList<>(matches.size());
        for (int index = matches.size() - 1; index >= 0; index--) {
            newestFirst.add(matches.get(index));
        }
        recentMatches.addAll(0, newestFirst);
        for (LogMonitorMatch match : matches) {
            info("命中规则 [" + match.ruleName() + "]: " + match.line());
        }
        if (recentMatches.size() > MAX_RECENT_MATCHES) {
            recentMatches.remove(MAX_RECENT_MATCHES, recentMatches.size());
        }
        updateMatchCount();
    }

    private void updateMatchCount() {
        matchCountLabel.setText(recentMatches.size() + " 条");
    }

    private void updateActionButtons() {
        boolean running = monitorService.isRunning();
        startMonitorButton.setDisable(running);
        stopMonitorButton.setDisable(!running);
        saveConfigButton.setDisable(cleanedUp);
        boolean selected = ruleTable.getSelectionModel().getSelectedItem() != null;
        updateRuleButton.setDisable(!selected);
        deleteRuleButton.setDisable(!selected);
    }

    private void reportValidationError(String message) {
        String detail = message == null || message.isBlank() ? "配置无效" : message;
        error(detail);
        showStatus("配置无效", LogMonitorStatus.ERROR, detail);
    }

    private void showStatus(String text, LogMonitorStatus status, String detail) {
        monitorStatusLabel.setText(text);
        monitorStatusLabel.getStyleClass().removeAll(
                "status-online", "status-offline", "status-error");
        monitorStatusLabel.getStyleClass().add(switch (status) {
            case RUNNING -> "status-online";
            case ERROR -> "status-error";
            case STOPPED, WAITING_FOR_FILE -> "status-offline";
        });
        String tooltip = detail == null || detail.isBlank() ? text : text + "\n" + detail;
        monitorStatusLabel.setTooltip(new Tooltip(tooltip));
    }

    private void dispatchToFx(Runnable task) {
        if (cleanedUp) {
            return;
        }
        try {
            Platform.runLater(() -> {
                if (!cleanedUp) {
                    task.run();
                }
            });
        } catch (IllegalStateException ignored) {
            // JavaFX is shutting down; cleanup owns service termination.
        }
    }

    private static String modeLabel(LogMatchMode mode) {
        return switch (mode) {
            case CONTAINS -> "包含文本";
            case WHOLE_TOKEN -> "完整词元";
            case REGEX -> "正则表达式";
        };
    }

    private final class MonitorListener implements LogMonitorService.Listener {
        @Override
        public void onStatusChanged(LogMonitorStatus status, String detail) {
            dispatchToFx(() -> {
                switch (status) {
                    case STOPPED -> showStatus("已停止", status, detail);
                    case WAITING_FOR_FILE -> showStatus("等待日志文件", status, detail);
                    case RUNNING -> showStatus("监听中", status, detail);
                    case ERROR -> showStatus("读取错误", status, detail);
                }
                updateActionButtons();
            });
        }

        @Override
        public void onMatches(List<LogMonitorMatch> matches) {
            alertService.acceptAll(matches);
            automationService.acceptAll(matches);
            matchDispatcher.submit(matches);
        }

        @Override
        public void onReadError(String message) {
            dispatchToFx(() -> error(message));
        }
    }
}
