package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.StringConverter;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.service.NetworkQualityReportFormatter;
import plugin.javafxtools.service.NetworkQualityService;
import plugin.javafxtools.service.NetworkQualityService.MonitorSnapshot;
import plugin.javafxtools.service.NetworkQualityService.ProbeSample;
import plugin.javafxtools.service.NetworkQualityService.Protocol;
import plugin.javafxtools.service.NetworkQualityService.ProxyEndpoint;
import plugin.javafxtools.service.NetworkQualityService.ProxySettings;
import plugin.javafxtools.service.NetworkQualityService.ProxyType;
import plugin.javafxtools.service.NetworkQualityService.Quality;
import plugin.javafxtools.service.NetworkQualityService.Route;
import plugin.javafxtools.service.NetworkQualityService.RoutePlan;
import plugin.javafxtools.service.NetworkQualityService.SessionSnapshot;
import plugin.javafxtools.service.NetworkQualityService.Target;
import plugin.javafxtools.service.NetworkQualityService.TargetEndpoint;
import plugin.javafxtools.service.NetworkQualitySettingsStore;
import plugin.javafxtools.service.NetworkQualitySettingsStore.Settings;
import plugin.javafxtools.service.NetworkQualityTargetStore;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Controller for HTTP and advanced endpoint availability monitoring. */
public final class NetworkQualityController extends BaseController {
    private static final int DEFAULT_INTERVAL_MILLIS = 2_000;
    private static final int DEFAULT_TIMEOUT_MILLIS = 3_000;

    @FXML private ComboBox<Protocol> protocolComboBox;
    @FXML private TextField targetNameField;
    @FXML private TextField targetEndpointField;
    @FXML private Button addTargetButton;
    @FXML private Button removeTargetButton;
    @FXML private Button restoreDefaultsButton;
    @FXML private ComboBox<RoutePlan> routePlanComboBox;
    @FXML private ComboBox<ProxyType> proxyTypeComboBox;
    @FXML private TextField proxyHostField;
    @FXML private TextField proxyPortField;
    @FXML private TextField proxyUsernameField;
    @FXML private PasswordField proxyPasswordField;
    @FXML private Label proxyCapabilityLabel;
    @FXML private ComboBox<Integer> intervalComboBox;
    @FXML private ComboBox<Integer> timeoutComboBox;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Button resetButton;
    @FXML private Button copyReportButton;
    @FXML private Label sessionStatusLabel;
    @FXML private Label qualityValueLabel;
    @FXML private Label qualityMetaLabel;
    @FXML private Label durationValueLabel;
    @FXML private Label durationMetaLabel;
    @FXML private Label systemRttValueLabel;
    @FXML private Label systemRttMetaLabel;
    @FXML private Label systemStabilityMetaLabel;
    @FXML private Label proxyRttValueLabel;
    @FXML private Label proxyRttMetaLabel;
    @FXML private Label proxyStabilityMetaLabel;
    @FXML private Label failureValueLabel;
    @FXML private Label failureMetaLabel;
    @FXML private ToggleButton systemTrendButton;
    @FXML private ToggleButton proxyTrendButton;
    @FXML private Label selectedNameLabel;
    @FXML private Label selectedEndpointLabel;
    @FXML private Label selectedQualityLabel;
    @FXML private Label selectedResponseLabel;
    @FXML private Label chartTargetLabel;
    @FXML private Label chartMetaLabel;
    @FXML private Label chartEmptyLabel;
    @FXML private Label routeDetailLabel;
    @FXML private LineChart<Number, Number> rttChart;
    @FXML private TableView<TargetRow> targetTable;
    @FXML private TableColumn<TargetRow, TargetRow> enabledColumn;
    @FXML private TableColumn<TargetRow, String> protocolColumn;
    @FXML private TableColumn<TargetRow, String> targetColumn;
    @FXML private TableColumn<TargetRow, String> systemQualityColumn;
    @FXML private TableColumn<TargetRow, String> systemRttColumn;
    @FXML private TableColumn<TargetRow, String> systemFailureColumn;
    @FXML private TableColumn<TargetRow, String> proxyQualityColumn;
    @FXML private TableColumn<TargetRow, String> proxyRttColumn;
    @FXML private TableColumn<TargetRow, String> proxyFailureColumn;
    @FXML private TableColumn<TargetRow, String> deltaColumn;
    @FXML private TableColumn<TargetRow, String> detailColumn;

    private final NetworkQualityService qualityService = new NetworkQualityService();
    private final NetworkQualityTargetStore targetStore = new NetworkQualityTargetStore();
    private final NetworkQualitySettingsStore settingsStore = new NetworkQualitySettingsStore();
    private final ObservableList<TargetRow> rows = FXCollections.observableArrayList();
    private final AtomicReference<SessionSnapshot> pendingSnapshot = new AtomicReference<>();
    private final AtomicBoolean uiUpdateQueued = new AtomicBoolean();
    private volatile boolean cleaned;
    private boolean running;
    private SessionSnapshot latestSnapshot;
    private String initializationWarning;
    private String sessionWarning;

    @FXML
    public void initialize() {
        configureInputs();
        configureTable();
        configureChart();
        loadTargets();
        loadSettings();
        targetTable.setItems(rows);
        targetTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    updateButtonStates();
                    renderChart();
                });
        if (!rows.isEmpty()) {
            targetTable.getSelectionModel().selectFirst();
        }
        resetMetrics();
        updateProxyControls();
        setSessionStatus(initializationWarning == null ? "READY" : "ERROR",
                initializationWarning == null ? "等待开始" : initializationWarning);
        updateButtonStates();
    }

    @FXML
    private void handleAddTarget() {
        if (running || rows.size() >= NetworkQualityService.MAX_TARGETS) {
            return;
        }
        Protocol protocol = protocolComboBox.getValue();
        String name = targetNameField.getText().strip();
        String endpointText = targetEndpointField.getText().strip();
        if (protocol == null || endpointText.isEmpty()) {
            setSessionStatus("ERROR", "请填写要监控的服务地址");
            return;
        }
        TargetEndpoint endpoint;
        try {
            endpoint = TargetEndpoint.parse(endpointText, protocol);
        } catch (IllegalArgumentException e) {
            setSessionStatus("ERROR", errorMessage(e));
            return;
        }
        boolean duplicate = rows.stream().anyMatch(row ->
                row.target.protocol() == endpoint.protocol()
                        && row.target.host().equalsIgnoreCase(endpoint.host())
                        && row.target.port() == endpoint.port()
                        && row.target.requestTarget().equals(endpoint.requestTarget()));
        if (duplicate) {
            setSessionStatus("ERROR", "该监控端点已经存在");
            return;
        }
        Target target = new Target(UUID.randomUUID().toString(),
                name.isEmpty() ? endpoint.defaultName() : name,
                endpoint.protocol(), endpoint.host(), endpoint.port(),
                endpoint.requestTarget(), true);
        TargetRow row = new TargetRow(target);
        rows.add(row);
        targetTable.getSelectionModel().select(row);
        targetNameField.clear();
        targetEndpointField.clear();
        saveTargets("目标已添加");
        updateButtonStates();
    }

    @FXML
    private void handleRemoveTarget() {
        TargetRow selected = targetTable.getSelectionModel().getSelectedItem();
        if (running || selected == null || rows.size() <= 1) {
            return;
        }
        int index = targetTable.getSelectionModel().getSelectedIndex();
        rows.remove(selected);
        targetTable.getSelectionModel().select(Math.min(index, rows.size() - 1));
        saveTargets("目标已移除");
        updateButtonStates();
    }

    @FXML
    private void handleRestoreDefaults() {
        if (running) {
            return;
        }
        rows.setAll(NetworkQualityTargetStore.defaultTargets().stream()
                .map(TargetRow::new).toList());
        targetTable.getSelectionModel().selectFirst();
        pendingSnapshot.set(null);
        latestSnapshot = null;
        saveTargets("已恢复默认监控目标");
        resetMetrics();
        updateButtonStates();
    }

    @FXML
    private void handleRoutePlanChanged() {
        updateProxyControls();
        updateTrendRouteAvailability();
        updateButtonStates();
    }

    @FXML
    private void handleProxyTypeChanged() {
        updateProxyControls();
    }

    @FXML
    private void handleStart() {
        if (running) {
            return;
        }
        RoutePlan plan = routePlanComboBox.getValue();
        if (plan == null) {
            setSessionStatus("ERROR", "请选择出口模式");
            return;
        }
        ProxySettings proxy = null;
        if (plan.usesProxy()) {
            proxy = readProxySettings();
            if (proxy == null) {
                return;
            }
        }
        List<Target> targets = rows.stream().map(TargetRow::currentTarget).toList();
        if (targets.stream().noneMatch(Target::enabled)) {
            setSessionStatus("ERROR", "请至少启用一个监控目标");
            return;
        }
        int interval = selectedOrDefault(intervalComboBox, DEFAULT_INTERVAL_MILLIS);
        int timeout = selectedOrDefault(timeoutComboBox, DEFAULT_TIMEOUT_MILLIS);
        sessionWarning = saveSettings(plan, proxy, interval, timeout)
                ? null : "出口设置未保存";
        pendingSnapshot.set(null);
        latestSnapshot = null;
        clearRowResults();
        resetMetrics();
        selectInitialTrendRoute(plan);
        running = true;
        setSessionStatus("BUSY", runningStatus());
        updateButtonStates();
        try {
            qualityService.start(targets, plan, proxy, Duration.ofMillis(interval),
                    Duration.ofMillis(timeout), this::queueSnapshot);
        } catch (RuntimeException e) {
            running = false;
            setSessionStatus("ERROR", errorMessage(e));
            updateButtonStates();
        }
    }

    @FXML
    private void handleStop() {
        if (!running) {
            return;
        }
        qualityService.stop();
        running = false;
        setSessionStatus("READY", "监控已停止");
        updateButtonStates();
    }

    @FXML
    private void handleReset() {
        qualityService.stop();
        running = false;
        pendingSnapshot.set(null);
        latestSnapshot = null;
        sessionWarning = null;
        clearRowResults();
        resetMetrics();
        setSessionStatus("READY", "结果已重置");
        updateButtonStates();
    }

    @FXML
    private void handleTrendRouteChanged() {
        if (!systemTrendButton.isSelected() && !proxyTrendButton.isSelected()) {
            systemTrendButton.setSelected(true);
        }
        renderChart();
    }

    @FXML
    private void handleCopyReport() {
        SessionSnapshot snapshot = latestSnapshot;
        if (snapshot == null) {
            setSessionStatus("ERROR", "当前没有可复制的诊断结果");
            return;
        }
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(NetworkQualityReportFormatter.format(snapshot));
            if (Clipboard.getSystemClipboard().setContent(content)) {
                setSessionStatus(running ? "BUSY" : "SUCCESS", "诊断报告已复制");
            } else {
                setSessionStatus("ERROR", "无法写入系统剪贴板");
            }
        } catch (RuntimeException e) {
            setSessionStatus("ERROR", "系统剪贴板当前不可用");
        }
    }

    @Override
    public TextArea getLogArea() {
        return null;
    }

    @Override
    public void cleanup() {
        cleaned = true;
        pendingSnapshot.set(null);
        proxyPasswordField.clear();
        qualityService.close();
        super.cleanup();
    }

    private void configureInputs() {
        protocolComboBox.getItems().setAll(Protocol.values());
        protocolComboBox.setConverter(enumConverter(Protocol::displayName));
        protocolComboBox.getSelectionModel().select(Protocol.HTTP);
        targetNameField.setTextFormatter(lengthFormatter(32));
        targetEndpointField.setTextFormatter(lengthFormatter(2_048));

        routePlanComboBox.getItems().setAll(RoutePlan.values());
        routePlanComboBox.setConverter(enumConverter(RoutePlan::displayName));
        routePlanComboBox.valueProperty().addListener(
                (observable, previous, selected) -> handleRoutePlanChanged());
        proxyTypeComboBox.getItems().setAll(ProxyType.values());
        proxyTypeComboBox.setConverter(enumConverter(ProxyType::displayName));
        proxyTypeComboBox.valueProperty().addListener(
                (observable, previous, selected) -> handleProxyTypeChanged());
        proxyHostField.setTextFormatter(lengthFormatter(512));
        proxyPortField.setTextFormatter(portFormatter());
        proxyUsernameField.setTextFormatter(lengthFormatter(128));
        proxyPasswordField.setTextFormatter(lengthFormatter(255));

        intervalComboBox.getItems().setAll(1_000, 2_000, 5_000, 10_000, 30_000);
        timeoutComboBox.getItems().setAll(500, 1_000, 2_000, 3_000, 5_000);
        StringConverter<Integer> durationConverter = new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                if (value == null) {
                    return "";
                }
                return value < 1_000 ? value + " ms"
                        : formatDecimal(value / 1_000.0) + " 秒";
            }

            @Override
            public Integer fromString(String value) {
                return null;
            }
        };
        intervalComboBox.setConverter(durationConverter);
        timeoutComboBox.setConverter(durationConverter);
    }

    private void configureTable() {
        enabledColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        enabledColumn.setCellFactory(column -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            {
                checkBox.setOnAction(event -> {
                    TargetRow row = getItem();
                    if (row != null && !running) {
                        row.setEnabled(checkBox.isSelected());
                        saveTargets("目标启用状态已更新");
                        updateButtonStates();
                    }
                });
            }

            @Override
            protected void updateItem(TargetRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                } else {
                    checkBox.setSelected(row.target.enabled());
                    checkBox.setDisable(running);
                    setGraphic(checkBox);
                }
            }
        });
        protocolColumn.setCellValueFactory(data -> value(data.getValue().protocolText()));
        targetColumn.setCellValueFactory(data -> value(data.getValue().targetSummaryText()));
        systemQualityColumn.setCellValueFactory(data -> value(
                data.getValue().qualityText(Route.SYSTEM)));
        systemRttColumn.setCellValueFactory(data -> value(data.getValue().rttText(Route.SYSTEM)));
        systemFailureColumn.setCellValueFactory(data -> value(
                data.getValue().failureText(Route.SYSTEM)));
        proxyQualityColumn.setCellValueFactory(data -> value(
                data.getValue().qualityText(Route.PROXY)));
        proxyRttColumn.setCellValueFactory(data -> value(data.getValue().rttText(Route.PROXY)));
        proxyFailureColumn.setCellValueFactory(data -> value(
                data.getValue().failureText(Route.PROXY)));
        deltaColumn.setCellValueFactory(data -> value(data.getValue().deltaText()));
        detailColumn.setCellValueFactory(data -> value(data.getValue().detailText()));
        targetTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        targetTable.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(TargetRow row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().removeIf(style -> style.startsWith("network-quality-row-"));
                if (!empty && row != null) {
                    getStyleClass().add("network-quality-row-"
                            + row.worstQuality().name().toLowerCase(Locale.ROOT));
                }
            }
        });
    }

    private void configureChart() {
        ToggleGroup trendGroup = new ToggleGroup();
        systemTrendButton.setToggleGroup(trendGroup);
        proxyTrendButton.setToggleGroup(trendGroup);
        systemTrendButton.setSelected(true);
        rttChart.setAnimated(false);
        rttChart.setCreateSymbols(true);
        rttChart.setLegendVisible(false);
    }

    private void loadTargets() {
        try {
            rows.setAll(targetStore.load().stream().map(TargetRow::new).toList());
        } catch (IOException e) {
            rows.setAll(NetworkQualityTargetStore.defaultTargets().stream()
                    .map(TargetRow::new).toList());
            initializationWarning = "目标配置读取失败，已使用默认值";
        }
    }

    private void loadSettings() {
        Settings settings;
        try {
            settings = settingsStore.load();
        } catch (IOException e) {
            settings = Settings.defaults();
            initializationWarning = initializationWarning == null
                    ? "出口设置读取失败，已使用默认值" : initializationWarning + "；出口设置已重置";
        }
        routePlanComboBox.getSelectionModel().select(settings.routePlan());
        if (settings.proxyHost().isBlank()) {
            proxyTypeComboBox.getSelectionModel().select(settings.proxyType());
            proxyHostField.clear();
            proxyPortField.setText(Integer.toString(settings.proxyPort()));
        } else {
            try {
                ProxyEndpoint endpoint = ProxyEndpoint.parse(settings.proxyHost(),
                        settings.proxyType(), settings.proxyPort());
                proxyTypeComboBox.getSelectionModel().select(endpoint.type());
                proxyHostField.setText(endpoint.host());
                proxyPortField.setText(Integer.toString(endpoint.port()));
            } catch (IllegalArgumentException e) {
                proxyTypeComboBox.getSelectionModel().select(settings.proxyType());
                proxyHostField.setText(settings.proxyHost());
                proxyPortField.setText(Integer.toString(settings.proxyPort()));
                if (settings.routePlan().usesProxy()) {
                    initializationWarning = initializationWarning == null
                            ? "代理地址格式无效，请重新填写"
                            : initializationWarning + "；代理地址需重新填写";
                }
            }
        }
        proxyUsernameField.setText(settings.proxyUsername());
        intervalComboBox.getSelectionModel().select(Integer.valueOf(settings.intervalMillis()));
        timeoutComboBox.getSelectionModel().select(Integer.valueOf(settings.timeoutMillis()));
        if (intervalComboBox.getValue() == null) {
            intervalComboBox.getSelectionModel().select(Integer.valueOf(DEFAULT_INTERVAL_MILLIS));
        }
        if (timeoutComboBox.getValue() == null) {
            timeoutComboBox.getSelectionModel().select(Integer.valueOf(DEFAULT_TIMEOUT_MILLIS));
        }
    }

    private void saveTargets(String successMessage) {
        try {
            targetStore.save(rows.stream().map(TargetRow::currentTarget).toList());
            setSessionStatus("READY", successMessage);
        } catch (IOException e) {
            setSessionStatus("ERROR", errorMessage(e));
        }
    }

    private boolean saveSettings(RoutePlan plan, ProxySettings proxy,
                                 int interval, int timeout) {
        ProxyType type = proxy == null
                ? proxyTypeComboBox.getValue() == null
                ? ProxyType.SOCKS5 : proxyTypeComboBox.getValue()
                : proxy.type();
        int port = proxy == null
                ? parsePortValue(proxyPortField.getText(), 1_080) : proxy.port();
        String host = proxy == null ? proxyHostField.getText().strip() : proxy.host();
        Settings settings = new Settings(plan, type, host, port,
                proxyUsernameField.getText(), interval, timeout);
        try {
            settingsStore.save(settings);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private ProxySettings readProxySettings() {
        ProxyType type = proxyTypeComboBox.getValue();
        String address = proxyHostField.getText().strip();
        if (type == null || address.isEmpty()) {
            setSessionStatus("ERROR", "请完整填写代理类型和地址");
            return null;
        }
        try {
            int fallbackPort = parsePortValue(proxyPortField.getText(),
                    type == ProxyType.HTTP_CONNECT ? 8080 : 1_080);
            ProxyEndpoint endpoint = ProxyEndpoint.parse(address, type, fallbackPort);
            proxyTypeComboBox.getSelectionModel().select(endpoint.type());
            proxyHostField.setText(endpoint.host());
            proxyPortField.setText(Integer.toString(endpoint.port()));
            return new ProxySettings(endpoint.type(), endpoint.host(), endpoint.port(),
                    proxyUsernameField.getText(), proxyPasswordField.getText());
        } catch (IllegalArgumentException e) {
            setSessionStatus("ERROR", errorMessage(e));
            return null;
        }
    }

    private void queueSnapshot(SessionSnapshot snapshot) {
        if (cleaned) {
            return;
        }
        pendingSnapshot.set(snapshot);
        if (uiUpdateQueued.compareAndSet(false, true)) {
            try {
                Platform.runLater(this::flushSnapshot);
            } catch (IllegalStateException e) {
                uiUpdateQueued.set(false);
            }
        }
    }

    private void flushSnapshot() {
        try {
            SessionSnapshot snapshot = pendingSnapshot.getAndSet(null);
            if (snapshot != null && !cleaned) {
                renderSnapshot(snapshot);
            }
        } finally {
            uiUpdateQueued.set(false);
            if (pendingSnapshot.get() != null && !cleaned
                    && uiUpdateQueued.compareAndSet(false, true)) {
                Platform.runLater(this::flushSnapshot);
            }
        }
    }

    private void renderSnapshot(SessionSnapshot snapshot) {
        latestSnapshot = snapshot;
        running = snapshot.running();
        for (TargetRow row : rows) {
            row.clear();
        }
        for (MonitorSnapshot monitor : snapshot.monitors()) {
            rows.stream().filter(row -> row.target.id().equals(monitor.target().id()))
                    .findFirst().ifPresent(row -> row.apply(monitor));
        }
        targetTable.refresh();
        updateSummary(snapshot);
        updateTrendRouteAvailability();
        renderChart();
        setSessionStatus(snapshot.running() ? "BUSY" : "READY",
                snapshot.running() ? runningStatus() : "监控已停止");
        updateButtonStates();
    }

    private void updateSummary(SessionSnapshot snapshot) {
        List<MonitorSnapshot> supported = snapshot.monitors().stream()
                .filter(MonitorSnapshot::supported).toList();
        long sent = supported.stream().mapToLong(MonitorSnapshot::sent).sum();
        List<ProbeSample> recent = recentSamples(supported);
        long recentSuccess = recent.stream().filter(ProbeSample::success).count();
        long recentFailed = recent.size() - recentSuccess;
        Quality overall = overallQuality(supported);
        qualityValueLabel.setText(overall.displayName());
        qualityMetaLabel.setText(supported.size() + " 路有效监控");
        durationValueLabel.setText(formatDuration(snapshot.elapsed()));
        durationMetaLabel.setText(Long.toString(sent));
        failureValueLabel.setText(recent.isEmpty() ? "--"
                : formatPercent(recentSuccess * 100.0 / recent.size()));
        failureMetaLabel.setText(recent.isEmpty() ? "暂无近期样本"
                : recentSuccess + " 成功 · " + recentFailed + " 失败");
    }

    private static List<ProbeSample> recentSamples(List<MonitorSnapshot> monitors) {
        List<ProbeSample> result = new ArrayList<>();
        for (MonitorSnapshot monitor : monitors) {
            List<ProbeSample> history = monitor.history();
            int start = Math.max(0,
                    history.size() - NetworkQualityService.RECENT_QUALITY_SAMPLES);
            result.addAll(history.subList(start, history.size()));
        }
        return result;
    }

    private void renderChart() {
        rttChart.getData().clear();
        chartEmptyLabel.setVisible(true);
        chartEmptyLabel.setManaged(true);
        TargetRow row = targetTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            selectedNameLabel.setText("未选择目标");
            selectedEndpointLabel.setText("--");
            setSelectedQuality(Quality.WAITING);
            selectedResponseLabel.setText("等待响应");
            renderRouteMetric(null, systemRttValueLabel, systemRttMetaLabel,
                    systemStabilityMetaLabel);
            renderRouteMetric(null, proxyRttValueLabel, proxyRttMetaLabel,
                    proxyStabilityMetaLabel);
            chartTargetLabel.setText("未选择目标");
            chartMetaLabel.setText("--");
            routeDetailLabel.setText("选择目标查看出口与响应详情");
            chartEmptyLabel.setText("选择端点后查看趋势");
            return;
        }
        Route route = proxyTrendButton.isSelected() ? Route.PROXY : Route.SYSTEM;
        MonitorSnapshot snapshot = row.snapshot(route);
        selectedNameLabel.setText(row.target.name());
        selectedEndpointLabel.setText(row.selectedEndpointText());
        renderRouteMetric(row.snapshot(Route.SYSTEM), systemRttValueLabel,
                systemRttMetaLabel, systemStabilityMetaLabel);
        renderRouteMetric(row.snapshot(Route.PROXY), proxyRttValueLabel,
                proxyRttMetaLabel, proxyStabilityMetaLabel);
        setSelectedQuality(snapshot == null ? row.worstQuality() : snapshot.quality());
        selectedResponseLabel.setText(latestOutcome(snapshot));
        chartTargetLabel.setText(row.target.name() + " · " + route.displayName());
        if (snapshot == null) {
            chartMetaLabel.setText("当前会话未启用该出口");
            routeDetailLabel.setText(route == Route.SYSTEM
                    ? "系统路由：由 Windows 路由表决定实际路径"
                    : "显式代理：等待代理监控会话");
            chartEmptyLabel.setText("当前会话未启用该出口");
            return;
        }
        if (!snapshot.supported()) {
            chartMetaLabel.setText(snapshot.lastError());
            routeDetailLabel.setText("该代理协议组合不会计入失败率");
            chartEmptyLabel.setText("当前协议与代理组合不受支持");
            return;
        }
        XYChart.Series<Number, Number> rttSeries = new XYChart.Series<>();
        XYChart.Series<Number, Number> failureSeries = new XYChart.Series<>();
        List<ProbeSample> history = snapshot.history();
        Instant origin = history.isEmpty() ? null : history.getFirst().capturedAt();
        for (ProbeSample sample : history) {
            double elapsedSeconds = origin == null ? 0
                    : Duration.between(origin, sample.capturedAt()).toMillis() / 1_000.0;
            if (sample.success()) {
                rttSeries.getData().add(new XYChart.Data<>(elapsedSeconds, sample.rttMillis()));
            } else {
                failureSeries.getData().add(new XYChart.Data<>(elapsedSeconds, 0));
            }
        }
        if (!history.isEmpty()) {
            rttChart.getData().addAll(rttSeries, failureSeries);
            chartEmptyLabel.setVisible(false);
            chartEmptyLabel.setManaged(false);
        } else {
            chartEmptyLabel.setText("开始监控后显示实时趋势");
        }
        boolean hasRecentSuccess = Double.isFinite(snapshot.averageRttMillis());
        chartMetaLabel.setText(history.isEmpty() ? "等待探测样本"
                : (Double.isNaN(snapshot.lastRttMillis()) ? "当前请求失败"
                : "当前 " + formatMillis(snapshot.lastRttMillis()))
                + (!hasRecentSuccess ? "" : " · 均值 "
                + formatMillis(snapshot.averageRttMillis()) + " · P95 "
                + formatMillis(snapshot.p95RttMillis()) + " · 抖动 "
                + formatMillis(snapshot.jitterMillis()))
                + " · 可用 " + formatPercent(100 - snapshot.failurePercent()));
        List<String> details = new ArrayList<>();
        if (snapshot.connection() != null) {
            details.add(snapshot.connection().displayValue());
        }
        if (snapshot.mapping() != null) {
            details.add("公网映射 " + snapshot.mapping().displayValue()
                    + " · 变化 " + snapshot.mappingChanges() + " 次");
        }
        if (!snapshot.lastError().isBlank()) {
            details.add("最近错误: " + snapshot.lastError());
        }
        if (snapshot.consecutiveFailures() > 0) {
            details.add("连续失败 " + snapshot.consecutiveFailures() + " 次");
        }
        if (!snapshot.lastResponse().isBlank()) {
            details.add("最近响应: " + snapshot.lastResponse());
        }
        routeDetailLabel.setText(details.isEmpty()
                ? "等待本地源地址和网卡信息" : String.join(" · ", details));
    }

    private void renderRouteMetric(MonitorSnapshot snapshot,
                                   Label valueLabel, Label metaLabel,
                                   Label stabilityLabel) {
        if (snapshot == null) {
            valueLabel.setText("--");
            metaLabel.setText("当前会话未启用");
            stabilityLabel.setText("");
            return;
        }
        if (!snapshot.supported()) {
            valueLabel.setText("不支持");
            metaLabel.setText(snapshot.lastError());
            stabilityLabel.setText("");
            return;
        }
        valueLabel.setText(Double.isNaN(snapshot.lastRttMillis())
                ? snapshot.sent() == 0 ? "--" : "请求失败"
                : formatMillis(snapshot.lastRttMillis()));
        boolean hasRecentSuccess = Double.isFinite(snapshot.averageRttMillis());
        metaLabel.setText(snapshot.sent() == 0 ? "等待样本"
                : !hasRecentSuccess
                ? "近期无成功响应"
                : "均 " + formatMillis(snapshot.averageRttMillis())
                + " · P95 " + formatMillis(snapshot.p95RttMillis()));
        stabilityLabel.setText(snapshot.sent() == 0 ? ""
                : !hasRecentSuccess
                ? "可用 " + formatPercent(100 - snapshot.failurePercent())
                : "抖动 " + formatMillis(snapshot.jitterMillis())
                + " · 可用 " + formatPercent(100 - snapshot.failurePercent()));
    }

    private String latestOutcome(MonitorSnapshot snapshot) {
        if (snapshot == null) {
            return "等待响应";
        }
        if (!snapshot.lastError().isBlank()) {
            return snapshot.lastError();
        }
        return snapshot.lastResponse().isBlank() ? "连接已建立" : snapshot.lastResponse();
    }

    private void setSelectedQuality(Quality quality) {
        selectedQualityLabel.setText(quality.displayName());
        selectedQualityLabel.getStyleClass().removeIf(
                style -> style.startsWith("network-quality-quality-"));
        selectedQualityLabel.getStyleClass().add("network-quality-quality-"
                + quality.name().toLowerCase(Locale.ROOT));
    }

    private void clearRowResults() {
        rows.forEach(TargetRow::clear);
        targetTable.refresh();
        renderChart();
    }

    private void resetMetrics() {
        qualityValueLabel.setText("等待");
        qualityMetaLabel.setText(rows.stream().filter(row -> row.target.enabled()).count()
                + " 个目标已启用");
        durationValueLabel.setText("00:00:00");
        durationMetaLabel.setText("0");
        systemRttValueLabel.setText("--");
        systemRttMetaLabel.setText("当前会话未启用");
        systemStabilityMetaLabel.setText("");
        proxyRttValueLabel.setText("--");
        proxyRttMetaLabel.setText("当前会话未启用");
        proxyStabilityMetaLabel.setText("");
        failureValueLabel.setText("--");
        failureMetaLabel.setText("暂无近期样本");
        renderChart();
    }

    private void updateProxyControls() {
        RoutePlan plan = routePlanComboBox.getValue();
        boolean proxyEnabled = plan != null && plan.usesProxy() && !running;
        proxyTypeComboBox.setDisable(!proxyEnabled);
        proxyHostField.setDisable(!proxyEnabled);
        proxyPortField.setDisable(!proxyEnabled);
        proxyUsernameField.setDisable(!proxyEnabled);
        proxyPasswordField.setDisable(!proxyEnabled);
        ProxyType type = proxyTypeComboBox.getValue();
        proxyCapabilityLabel.setText(!proxyEnabled ? "系统路由由 Windows 路由表决定"
                : type == ProxyType.HTTP_CONNECT
                ? "HTTP CONNECT 支持 HTTP / HTTPS / TCP / TLS；不支持 STUN"
                : "SOCKS5 支持 HTTP / HTTPS / TCP / TLS / STUN，域名由代理解析");
    }

    private void updateTrendRouteAvailability() {
        RoutePlan plan = latestSnapshot == null
                ? routePlanComboBox.getValue() : latestSnapshot.routePlan();
        boolean hasSystem = plan == null || plan.usesSystemRoute();
        boolean hasProxy = plan != null && plan.usesProxy();
        systemTrendButton.setDisable(!hasSystem);
        proxyTrendButton.setDisable(!hasProxy);
        if (!hasSystem && systemTrendButton.isSelected()) {
            proxyTrendButton.setSelected(true);
        } else if (!hasProxy && proxyTrendButton.isSelected()) {
            systemTrendButton.setSelected(true);
        }
    }

    private void selectInitialTrendRoute(RoutePlan plan) {
        if (plan == RoutePlan.PROXY_ONLY) {
            proxyTrendButton.setSelected(true);
        } else {
            systemTrendButton.setSelected(true);
        }
        updateTrendRouteAvailability();
    }

    private void updateButtonStates() {
        boolean selected = targetTable.getSelectionModel().getSelectedItem() != null;
        boolean atLimit = rows.size() >= NetworkQualityService.MAX_TARGETS;
        boolean hasEnabled = rows.stream().anyMatch(row -> row.target.enabled());
        protocolComboBox.setDisable(running || atLimit);
        targetNameField.setDisable(running || atLimit);
        targetEndpointField.setDisable(running || atLimit);
        addTargetButton.setDisable(running || atLimit);
        removeTargetButton.setDisable(running || !selected || rows.size() <= 1);
        restoreDefaultsButton.setDisable(running);
        routePlanComboBox.setDisable(running);
        intervalComboBox.setDisable(running);
        timeoutComboBox.setDisable(running);
        startButton.setDisable(running || !hasEnabled);
        stopButton.setDisable(!running);
        resetButton.setDisable(!running && latestSnapshot == null
                && rows.stream().allMatch(row -> !row.hasResult()));
        copyReportButton.setDisable(latestSnapshot == null);
        updateProxyControls();
        targetTable.refresh();
    }

    private void setSessionStatus(String state, String message) {
        if (sessionStatusLabel == null) {
            return;
        }
        sessionStatusLabel.setText(message);
        sessionStatusLabel.getStyleClass().removeAll(
                "status-offline", "status-busy", "status-online", "status-error");
        sessionStatusLabel.getStyleClass().add(switch (state) {
            case "BUSY" -> "status-busy";
            case "SUCCESS" -> "status-online";
            case "ERROR" -> "status-error";
            default -> "status-offline";
        });
    }

    private static int parsePortValue(String text, int fallback) {
        try {
            int port = Integer.parseInt(text.strip());
            return port >= 1 && port <= 65_535 ? port : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String runningStatus() {
        return sessionWarning == null ? "持续监控中" : "持续监控中 · " + sessionWarning;
    }

    private static Quality overallQuality(List<MonitorSnapshot> snapshots) {
        List<Quality> order = List.of(Quality.WAITING, Quality.EXCELLENT, Quality.GOOD,
                Quality.DEGRADED, Quality.POOR, Quality.OFFLINE);
        return snapshots.stream().map(MonitorSnapshot::quality)
                .filter(quality -> quality != Quality.UNSUPPORTED)
                .max(java.util.Comparator.comparingInt(order::indexOf))
                .orElse(Quality.WAITING);
    }

    private static TextFormatter<String> lengthFormatter(int maxLength) {
        return new TextFormatter<>(change -> change.getControlNewText().length() <= maxLength
                ? change : null);
    }

    private static TextFormatter<String> portFormatter() {
        return new TextFormatter<>(change -> {
            String value = change.getControlNewText();
            return value.length() <= 5 && value.chars().allMatch(Character::isDigit)
                    ? change : null;
        });
    }

    private static <T> StringConverter<T> enumConverter(
            java.util.function.Function<T, String> formatter) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : formatter.apply(value);
            }

            @Override
            public T fromString(String value) {
                return null;
            }
        };
    }

    private static ReadOnlyStringWrapper value(String value) {
        return new ReadOnlyStringWrapper(value);
    }

    private static int selectedOrDefault(ComboBox<Integer> comboBox, int defaultValue) {
        return comboBox.getValue() == null ? defaultValue : comboBox.getValue();
    }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        return "%02d:%02d:%02d".formatted(
                seconds / 3_600, seconds % 3_600 / 60, seconds % 60);
    }

    private static String formatMillis(double value) {
        return Double.isFinite(value) ? formatDecimal(value) + " ms" : "--";
    }

    private static String formatPercent(double value) {
        return formatDecimal(value) + "%";
    }

    private static String formatDecimal(double value) {
        return value >= 100 ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    public static final class TargetRow {
        private Target target;
        private MonitorSnapshot systemSnapshot;
        private MonitorSnapshot proxySnapshot;

        private TargetRow(Target target) {
            this.target = target;
        }

        private Target currentTarget() {
            return target;
        }

        private void setEnabled(boolean enabled) {
            target = new Target(target.id(), target.name(), target.protocol(),
                    target.host(), target.port(), target.requestTarget(), enabled);
        }

        private void apply(MonitorSnapshot snapshot) {
            if (snapshot.route() == Route.SYSTEM) {
                systemSnapshot = snapshot;
            } else {
                proxySnapshot = snapshot;
            }
        }

        private void clear() {
            systemSnapshot = null;
            proxySnapshot = null;
        }

        private boolean hasResult() {
            return systemSnapshot != null || proxySnapshot != null;
        }

        private MonitorSnapshot snapshot(Route route) {
            return route == Route.SYSTEM ? systemSnapshot : proxySnapshot;
        }

        private String protocolText() {
            return switch (target.protocol()) {
                case HTTP -> "HTTP";
                case HTTPS -> "HTTPS";
                case STUN_UDP -> "UDP/STUN";
                case TCP -> "TCP";
                case TLS -> "TLS";
            };
        }

        private String targetSummaryText() {
            return target.name() + " · " + endpointText();
        }

        private String endpointText() {
            return new TargetEndpoint(target.protocol(), target.host(),
                    target.port(), target.requestTarget()).displayValue();
        }

        private String selectedEndpointText() {
            if (target.protocol() != Protocol.HTTP && target.protocol() != Protocol.HTTPS) {
                return endpointText();
            }
            String endpoint = endpointText();
            int pathIndex = endpoint.indexOf('/', endpoint.indexOf("//") + 2);
            return pathIndex < 0 ? endpoint
                    : endpoint.substring(0, pathIndex) + "\n" + endpoint.substring(pathIndex);
        }

        private String qualityText(Route route) {
            MonitorSnapshot value = snapshot(route);
            return value == null ? "--" : value.quality().displayName();
        }

        private String rttText(Route route) {
            MonitorSnapshot value = snapshot(route);
            return value == null || Double.isNaN(value.lastRttMillis())
                    ? "--" : formatMillis(value.lastRttMillis());
        }

        private String failureText(Route route) {
            MonitorSnapshot value = snapshot(route);
            return value == null || !value.supported()
                    ? "--" : value.sent() == 0 ? "--"
                    : formatPercent(100 - value.failurePercent());
        }

        private String deltaText() {
            if (systemSnapshot == null || proxySnapshot == null
                    || Double.isNaN(systemSnapshot.lastRttMillis())
                    || Double.isNaN(proxySnapshot.lastRttMillis())) {
                return "--";
            }
            double delta = proxySnapshot.lastRttMillis() - systemSnapshot.lastRttMillis();
            return (delta >= 0 ? "+" : "") + formatMillis(delta);
        }

        private String detailText() {
            String system = routeDetail(systemSnapshot, "系统");
            String proxy = routeDetail(proxySnapshot, "代理");
            if (system.isEmpty()) {
                return proxy.isEmpty() ? "--" : proxy;
            }
            return proxy.isEmpty() ? system : system + " | " + proxy;
        }

        private Quality worstQuality() {
            if (systemSnapshot == null && proxySnapshot != null) {
                return proxySnapshot.quality();
            }
            if (proxySnapshot == null && systemSnapshot != null) {
                return systemSnapshot.quality();
            }
            Quality system = systemSnapshot == null ? Quality.WAITING : systemSnapshot.quality();
            Quality proxy = proxySnapshot == null ? Quality.WAITING : proxySnapshot.quality();
            if (system == Quality.UNSUPPORTED) {
                return proxy;
            }
            if (proxy == Quality.UNSUPPORTED) {
                return system;
            }
            List<Quality> order = List.of(Quality.WAITING, Quality.EXCELLENT, Quality.GOOD,
                    Quality.DEGRADED, Quality.POOR, Quality.OFFLINE);
            return order.indexOf(system) >= order.indexOf(proxy) ? system : proxy;
        }

        private static String mappingValue(MonitorSnapshot snapshot) {
            if (snapshot == null || snapshot.mapping() == null) {
                return "--";
            }
            return snapshot.mapping().displayValue()
                    + (snapshot.mappingChanges() == 0
                    ? "" : " (变化 " + snapshot.mappingChanges() + ")");
        }

        private static String errorValue(MonitorSnapshot snapshot) {
            return snapshot == null || snapshot.lastError().isBlank()
                    ? "" : snapshot.lastError();
        }

        private static String routeDetail(MonitorSnapshot snapshot, String routeName) {
            if (snapshot == null) {
                return "";
            }
            List<String> details = new ArrayList<>();
            String mapping = mappingValue(snapshot);
            if (!mapping.equals("--")) {
                details.add(routeName + " " + mapping);
            }
            if (!snapshot.lastResponse().isBlank()) {
                details.add(routeName + " " + snapshot.lastResponse());
            }
            String error = errorValue(snapshot);
            if (!error.isEmpty()) {
                details.add(routeName + "错误: " + error);
            }
            if (snapshot.consecutiveFailures() > 0) {
                details.add(routeName + "连续失败 " + snapshot.consecutiveFailures() + " 次");
            }
            return String.join(" · ", details);
        }
    }
}
