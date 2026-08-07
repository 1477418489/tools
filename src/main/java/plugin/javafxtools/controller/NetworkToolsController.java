package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.service.IpInformationService;
import plugin.javafxtools.service.IpInformationService.IpInformation;
import plugin.javafxtools.service.NetworkDiagnosticService;
import plugin.javafxtools.service.NetworkDiagnosticService.DiagnosticMode;
import plugin.javafxtools.service.NetworkDiagnosticService.DiagnosticResult;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网络诊断页，负责域名解析、IP 分类和 TCP 端口连通性检测。
 */
public final class NetworkToolsController extends BaseController {
    private static final DateTimeFormatter RESULT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TARGET_LENGTH = 2_048;

    @FXML private TextField hostField;
    @FXML private ComboBox<String> portComboBox;
    @FXML private ComboBox<Integer> timeoutComboBox;
    @FXML private Button checkAllButton;
    @FXML private Button resolveButton;
    @FXML private Button portCheckButton;
    @FXML private Button ipLookupButton;
    @FXML private Button publicIpButton;
    @FXML private Button cancelButton;
    @FXML private Button clearButton;
    @FXML private Button copyButton;
    @FXML private Label lookupStatusLabel;
    @FXML private Label targetSummaryLabel;
    @FXML private Label targetMetaLabel;
    @FXML private Label dnsSummaryLabel;
    @FXML private Label dnsMetaLabel;
    @FXML private Label reachabilitySummaryLabel;
    @FXML private Label reachabilityMetaLabel;
    @FXML private Label portSummaryLabel;
    @FXML private Label portMetaLabel;
    @FXML private Label addressCountLabel;
    @FXML private Label ipInformationStatusLabel;
    @FXML private Label ipAddressValueLabel;
    @FXML private Label ipTypeValueLabel;
    @FXML private Label ipScopeValueLabel;
    @FXML private Label ipLocationValueLabel;
    @FXML private Label ipNetworkValueLabel;
    @FXML private Label ipTimezoneValueLabel;
    @FXML private Label ipCoordinatesValueLabel;
    @FXML private Label ipDataSourceValueLabel;
    @FXML private VBox dnsSummaryCard;
    @FXML private VBox reachabilitySummaryCard;
    @FXML private VBox portSummaryCard;
    @FXML private ListView<String> addressListView;
    @FXML private TextArea detailArea;
    @FXML private TabPane resultTabPane;
    @FXML private Tab diagnosticDetailTab;
    @FXML private Tab ipInfoTab;

    private final NetworkDiagnosticService diagnosticService =
            new NetworkDiagnosticService();
    private final IpInformationService ipInformationService =
            new IpInformationService();
    private final ExecutorService queryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NetworkDiagnostic");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong queryGeneration = new AtomicLong();

    private volatile Future<?> currentQuery;
    private boolean queryRunning;

    @FXML
    public void initialize() {
        configureInputs();
        hostField.textProperty().addListener(
                (observable, oldValue, newValue) -> updateButtonStates());
        portComboBox.getEditor().textProperty().addListener(
                (observable, oldValue, newValue) -> updateButtonStates());
        detailArea.textProperty().addListener(
                (observable, oldValue, newValue) -> updateButtonStates());
        resetResults();
        setStatus("READY", "就绪");
        updateButtonStates();
    }

    @FXML
    private void handleCheckAll() {
        startDiagnostic(DiagnosticMode.FULL);
    }

    @FXML
    private void handleResolve() {
        startDiagnostic(DiagnosticMode.RESOLVE_ONLY);
    }

    @FXML
    private void handlePortCheck() {
        startDiagnostic(DiagnosticMode.PORT_ONLY);
    }

    @FXML
    private void handleIpLookup() {
        startIpInformationLookup(false);
    }

    @FXML
    private void handlePublicIpLookup() {
        startIpInformationLookup(true);
    }

    @FXML
    private void handleCancel() {
        if (!queryRunning) {
            return;
        }
        cancelCurrentQuery();
        detailArea.setText("检测已取消");
        dnsSummaryLabel.setText("已取消");
        dnsMetaLabel.setText("未保留未完成的结果");
        setCardState(dnsSummaryCard, "network-summary-warning");
        setIpInformationStatus("READY", "已取消");
        setStatus("READY", "已取消");
        updateButtonStates();
    }

    @FXML
    private void handleClear() {
        cancelCurrentQuery();
        hostField.clear();
        portComboBox.getSelectionModel().clearSelection();
        portComboBox.getEditor().clear();
        resetResults();
        setStatus("READY", "就绪");
        updateButtonStates();
        hostField.requestFocus();
    }

    @FXML
    private void handleCopyResult() {
        String detail = detailArea.getText();
        if (detail == null || detail.isBlank()) {
            setStatus("ERROR", "当前没有可复制的详情");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(detail);
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("SUCCESS", "详情已复制");
    }

    @Override
    public TextArea getLogArea() {
        return null;
    }

    @Override
    public void log(String level, String message) {
        setStatus(level, message);
    }

    @Override
    public void cleanup() {
        queryGeneration.incrementAndGet();
        Future<?> query = currentQuery;
        if (query != null) {
            query.cancel(true);
        }
        currentQuery = null;
        queryExecutor.shutdownNow();
        super.cleanup();
    }

    private void configureInputs() {
        hostField.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= MAX_TARGET_LENGTH ? change : null));
        portComboBox.getItems().setAll("80", "443", "22", "3306", "5432", "6379", "8080");
        portComboBox.getEditor().setTextFormatter(new TextFormatter<>(change -> {
            String value = change.getControlNewText();
            return value.length() <= 5 && value.chars().allMatch(Character::isDigit)
                    ? change : null;
        }));
        timeoutComboBox.getItems().setAll(1_000, 3_000, 5_000, 10_000);
        timeoutComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                if (value == null) {
                    return "";
                }
                return value % 1_000 == 0
                        ? value / 1_000 + " 秒" : value + " ms";
            }

            @Override
            public Integer fromString(String value) {
                return null;
            }
        });
        timeoutComboBox.getSelectionModel().select(Integer.valueOf(3_000));
    }

    private void startDiagnostic(DiagnosticMode mode) {
        if (queryRunning) {
            return;
        }
        String target = value(hostField.getText());
        if (target.isEmpty()) {
            setStatus("ERROR", "请输入域名、IP 地址或 URL");
            hostField.requestFocus();
            return;
        }

        String port = value(portComboBox.getEditor().getText());
        int timeout = timeoutComboBox.getValue() == null
                ? 3_000 : timeoutComboBox.getValue();
        long queryId = queryGeneration.incrementAndGet();
        queryRunning = true;
        prepareRunningState(target, mode);
        setStatus("BUSY", modeStatus(mode));
        updateButtonStates();

        try {
            currentQuery = queryExecutor.submit(() -> {
                try {
                    DiagnosticResult result = diagnosticService.inspect(
                            target, port, timeout, mode);
                    finishDiagnostic(queryId, result);
                } catch (Exception exception) {
                    finishFailure(queryId, target, exception);
                }
            });
        } catch (RejectedExecutionException e) {
            queryRunning = false;
            currentQuery = null;
            setStatus("ERROR", "诊断服务已关闭");
            updateButtonStates();
        }
    }

    private void startIpInformationLookup(boolean currentPublicAddress) {
        if (queryRunning) {
            return;
        }
        String target = value(hostField.getText());
        if (!currentPublicAddress && target.isEmpty()) {
            setStatus("ERROR", "请输入要查询的 IP 或域名");
            hostField.requestFocus();
            return;
        }

        int timeout = timeoutComboBox.getValue() == null
                ? 3_000 : timeoutComboBox.getValue();
        long queryId = queryGeneration.incrementAndGet();
        queryRunning = true;
        String displayTarget = currentPublicAddress ? "当前公网出口" : target;
        prepareIpInformationState(displayTarget);
        setStatus("BUSY", currentPublicAddress ? "公网 IP 查询中" : "IP 信息查询中");
        updateButtonStates();

        try {
            currentQuery = queryExecutor.submit(() -> {
                try {
                    IpInformation information = currentPublicAddress
                            ? ipInformationService.lookupPublicAddress(timeout)
                            : ipInformationService.lookupTarget(target, timeout);
                    finishIpInformation(queryId, information);
                } catch (Exception exception) {
                    finishFailure(queryId, displayTarget, exception);
                }
            });
        } catch (RejectedExecutionException e) {
            queryRunning = false;
            currentQuery = null;
            setIpInformationStatus("ERROR", "服务已关闭");
            setStatus("ERROR", "诊断服务已关闭");
            updateButtonStates();
        }
    }

    private void prepareRunningState(String target, DiagnosticMode mode) {
        addressListView.getItems().clear();
        addressCountLabel.setText("0 个地址");
        detailArea.clear();
        targetSummaryLabel.setText(target);
        targetMetaLabel.setText(modeDisplayName(mode));
        dnsSummaryLabel.setText("解析中");
        dnsMetaLabel.setText("正在查询目标地址");
        reachabilitySummaryLabel.setText("等待");
        reachabilityMetaLabel.setText("等待解析完成");
        portSummaryLabel.setText("等待");
        portMetaLabel.setText("等待解析完成");
        setCardState(dnsSummaryCard, "network-summary-busy");
        setCardState(reachabilitySummaryCard, null);
        setCardState(portSummaryCard, null);
        resetIpInformation();
        resultTabPane.getSelectionModel().select(diagnosticDetailTab);
    }

    private void prepareIpInformationState(String target) {
        addressListView.getItems().clear();
        addressCountLabel.setText("0 个地址");
        detailArea.clear();
        targetSummaryLabel.setText(target);
        targetMetaLabel.setText("IP 归属查询");
        dnsSummaryLabel.setText("查询中");
        dnsMetaLabel.setText("正在识别目标 IP");
        reachabilitySummaryLabel.setText("未检测");
        reachabilityMetaLabel.setText("IP 查询不执行主机探测");
        portSummaryLabel.setText("未检测");
        portMetaLabel.setText("IP 查询不执行端口连接");
        setCardState(dnsSummaryCard, "network-summary-busy");
        setCardState(reachabilitySummaryCard, null);
        setCardState(portSummaryCard, null);
        resetIpInformation();
        setIpInformationStatus("BUSY", "查询中");
        resultTabPane.getSelectionModel().select(ipInfoTab);
    }

    private void finishDiagnostic(long queryId, DiagnosticResult result) {
        Platform.runLater(() -> {
            if (queryGeneration.get() != queryId) {
                return;
            }
            queryRunning = false;
            currentQuery = null;
            applyResult(result);
            updateButtonStates();
        });
    }

    private void applyResult(DiagnosticResult result) {
        resultTabPane.getSelectionModel().select(diagnosticDetailTab);
        var target = result.target();
        targetSummaryLabel.setText(target.host());
        targetMetaLabel.setText(target.source()
                + (target.port() == null ? "" : " · 端口 " + target.port()));

        addressListView.getItems().setAll(result.addresses().stream()
                .map(address -> address.address() + "    "
                        + address.family() + " · " + address.scope())
                .toList());
        addressCountLabel.setText(result.addresses().size() + " 个地址");
        dnsSummaryLabel.setText(result.addresses().size() + " 个地址");
        dnsMetaLabel.setText(result.dnsDurationMillis() + " ms · 解析正常");
        setCardState(dnsSummaryCard, "network-summary-success");

        var reachability = result.reachability();
        if (reachability.checked()) {
            reachabilitySummaryLabel.setText(reachability.reachable() ? "可达" : "未响应");
            reachabilityMetaLabel.setText(reachability.durationMillis()
                    + " ms · " + reachability.detail());
            setCardState(reachabilitySummaryCard, reachability.reachable()
                    ? "network-summary-success" : "network-summary-warning");
        } else {
            reachabilitySummaryLabel.setText("未检测");
            reachabilityMetaLabel.setText(reachability.detail());
            setCardState(reachabilitySummaryCard, null);
        }

        var port = result.portCheck();
        if (port.checked()) {
            portSummaryLabel.setText(port.port()
                    + (port.open() ? " 可连接" : " 不可连接"));
            String address = port.address() == null || port.address().isBlank()
                    ? "" : port.address() + " · ";
            portMetaLabel.setText(address + port.durationMillis() + " ms · " + port.detail());
            setCardState(portSummaryCard, port.open()
                    ? "network-summary-success" : "network-summary-error");
        } else {
            portSummaryLabel.setText(port.port() == null ? "未指定" : "未检测 " + port.port());
            portMetaLabel.setText(port.detail());
            setCardState(portSummaryCard, null);
        }

        detailArea.setText(formatDetails(result));
        if (port.checked() && !port.open()) {
            setStatus("WARNING", "端口不可连接");
        } else if (reachability.checked() && !reachability.reachable()) {
            setStatus("WARNING", "主机未响应");
        } else {
            setStatus("SUCCESS", "检测完成");
        }
    }

    private void finishIpInformation(long queryId, IpInformation information) {
        Platform.runLater(() -> {
            if (queryGeneration.get() != queryId) {
                return;
            }
            queryRunning = false;
            currentQuery = null;
            applyIpInformation(information);
            updateButtonStates();
        });
    }

    private void applyIpInformation(IpInformation information) {
        targetSummaryLabel.setText(information.ip());
        targetMetaLabel.setText(information.query().isBlank()
                ? information.type() : information.query() + " · " + information.type());
        addressListView.getItems().setAll(information.ip() + "    "
                + information.type() + " · " + information.scope());
        addressCountLabel.setText("1 个地址");
        dnsSummaryLabel.setText(information.local() ? "本地地址" : "公网地址");
        dnsMetaLabel.setText(information.type() + " · " + information.scope());
        setCardState(dnsSummaryCard, "network-summary-success");
        reachabilitySummaryLabel.setText("未检测");
        reachabilityMetaLabel.setText("IP 查询不执行主机探测");
        setCardState(reachabilitySummaryCard, null);
        portSummaryLabel.setText("未检测");
        portMetaLabel.setText("IP 查询不执行端口连接");
        setCardState(portSummaryCard, null);

        ipAddressValueLabel.setText(displayValue(information.ip()));
        ipTypeValueLabel.setText(displayValue(information.type()));
        ipScopeValueLabel.setText(displayValue(information.scope()));
        ipLocationValueLabel.setText(information.location());
        ipNetworkValueLabel.setText(information.network());
        ipTimezoneValueLabel.setText(information.timeZoneDisplay());
        ipCoordinatesValueLabel.setText(displayValue(information.coordinates()));
        ipDataSourceValueLabel.setText(information.dataSource() + " · " + information.note());
        setIpInformationStatus("SUCCESS", information.local() ? "本地识别" : "查询完成");
        detailArea.setText(formatIpInformation(information));
        resultTabPane.getSelectionModel().select(ipInfoTab);
        setStatus("SUCCESS", information.local() ? "本地 IP 已识别" : "IP 信息已更新");
    }

    private void finishFailure(long queryId, String target, Exception exception) {
        Platform.runLater(() -> {
            if (queryGeneration.get() != queryId) {
                return;
            }
            queryRunning = false;
            currentQuery = null;
            String message = diagnosticError(exception);
            targetSummaryLabel.setText(target);
            targetMetaLabel.setText("检测失败");
            dnsSummaryLabel.setText("解析失败");
            dnsMetaLabel.setText(message);
            reachabilitySummaryLabel.setText("未检测");
            reachabilityMetaLabel.setText("DNS / IP 未完成");
            portSummaryLabel.setText("未检测");
            portMetaLabel.setText("DNS / IP 未完成");
            addressListView.getItems().clear();
            addressCountLabel.setText("0 个地址");
            detailArea.setText("检测时间: " + RESULT_TIME_FORMAT.format(LocalDateTime.now())
                    + "\n检测目标: " + target + "\n检测结果: 失败\n失败原因: " + message);
            setCardState(dnsSummaryCard, "network-summary-error");
            setCardState(reachabilitySummaryCard, null);
            setCardState(portSummaryCard, null);
            setIpInformationStatus("ERROR", "查询失败");
            setStatus("ERROR", exception instanceof UnknownHostException
                    ? "DNS 解析失败" : "检测失败");
            updateButtonStates();
        });
    }

    private String formatDetails(DiagnosticResult result) {
        StringBuilder text = new StringBuilder();
        text.append("检测时间: ").append(RESULT_TIME_FORMAT.format(LocalDateTime.now())).append('\n');
        text.append("原始目标: ").append(result.target().original()).append('\n');
        text.append("标准主机: ").append(result.target().host()).append('\n');
        text.append("目标类型: ").append(result.target().source()).append('\n');
        text.append("目标端口: ").append(result.target().port() == null
                ? "未指定" : result.target().port()).append("\n\n");

        text.append("DNS / IP\n");
        text.append("  解析耗时: ").append(result.dnsDurationMillis()).append(" ms\n");
        for (var address : result.addresses()) {
            text.append("  ").append(address.address()).append("  ")
                    .append(address.family()).append("  ").append(address.scope()).append('\n');
        }

        var reachability = result.reachability();
        text.append("\n主机响应\n");
        text.append("  状态: ").append(reachability.checked()
                ? (reachability.reachable() ? "可达" : "未响应") : "未检测").append('\n');
        text.append("  详情: ").append(reachability.detail()).append('\n');
        if (reachability.checked()) {
            text.append("  耗时: ").append(reachability.durationMillis()).append(" ms\n");
        }

        var port = result.portCheck();
        text.append("\nTCP 端口\n");
        text.append("  状态: ").append(port.checked()
                ? (port.open() ? "可连接" : "不可连接") : "未检测").append('\n');
        if (port.port() != null) {
            text.append("  端口: ").append(port.port()).append('\n');
        }
        if (port.address() != null && !port.address().isBlank()) {
            text.append("  地址: ").append(port.address()).append('\n');
        }
        text.append("  详情: ").append(port.detail()).append('\n');
        if (port.checked()) {
            text.append("  耗时: ").append(port.durationMillis()).append(" ms\n");
        }
        text.append("\n总耗时: ").append(result.totalDurationMillis()).append(" ms");
        return text.toString();
    }

    private String formatIpInformation(IpInformation information) {
        return "查询时间: " + RESULT_TIME_FORMAT.format(LocalDateTime.now())
                + "\n查询目标: " + displayValue(information.query())
                + "\nIP 地址: " + displayValue(information.ip())
                + "\nIP 类型: " + displayValue(information.type())
                + "\n地址范围: " + displayValue(information.scope())
                + "\n位置: " + information.location()
                + "\n网络 / ASN: " + information.network()
                + "\n时区: " + information.timeZoneDisplay()
                + "\n坐标: " + displayValue(information.coordinates())
                + "\n数据来源: " + displayValue(information.dataSource())
                + "\n说明: " + displayValue(information.note());
    }

    private void resetResults() {
        targetSummaryLabel.setText("等待输入");
        targetMetaLabel.setText("域名、IPv4、IPv6 或 URL");
        dnsSummaryLabel.setText("未检测");
        dnsMetaLabel.setText("等待解析");
        reachabilitySummaryLabel.setText("未检测");
        reachabilityMetaLabel.setText("系统探测或 TCP 结果");
        portSummaryLabel.setText("未检测");
        portMetaLabel.setText("可手动填写或从 URL 推断");
        addressListView.getItems().clear();
        addressCountLabel.setText("0 个地址");
        detailArea.clear();
        setCardState(dnsSummaryCard, null);
        setCardState(reachabilitySummaryCard, null);
        setCardState(portSummaryCard, null);
        resetIpInformation();
        resultTabPane.getSelectionModel().select(diagnosticDetailTab);
    }

    private void resetIpInformation() {
        ipAddressValueLabel.setText("--");
        ipTypeValueLabel.setText("--");
        ipScopeValueLabel.setText("--");
        ipLocationValueLabel.setText("--");
        ipNetworkValueLabel.setText("--");
        ipTimezoneValueLabel.setText("--");
        ipCoordinatesValueLabel.setText("--");
        ipDataSourceValueLabel.setText("--");
        setIpInformationStatus("READY", "未查询");
    }

    private void cancelCurrentQuery() {
        queryGeneration.incrementAndGet();
        Future<?> query = currentQuery;
        if (query != null) {
            query.cancel(true);
        }
        currentQuery = null;
        queryRunning = false;
    }

    private void updateButtonStates() {
        boolean hasTarget = !value(hostField.getText()).isEmpty();
        boolean hasInput = hasTarget || !value(portComboBox.getEditor().getText()).isEmpty();
        boolean hasResult = !detailArea.getText().isBlank()
                || !addressListView.getItems().isEmpty();
        hostField.setDisable(queryRunning);
        portComboBox.setDisable(queryRunning);
        timeoutComboBox.setDisable(queryRunning);
        checkAllButton.setDisable(queryRunning || !hasTarget);
        resolveButton.setDisable(queryRunning || !hasTarget);
        portCheckButton.setDisable(queryRunning || !hasTarget);
        ipLookupButton.setDisable(queryRunning || !hasTarget);
        publicIpButton.setDisable(queryRunning);
        cancelButton.setDisable(!queryRunning);
        clearButton.setDisable(!queryRunning && !hasInput && !hasResult);
        copyButton.setDisable(!hasResult);
    }

    private void setStatus(String state, String message) {
        Runnable update = () -> {
            lookupStatusLabel.setText(message);
            lookupStatusLabel.getStyleClass().removeAll(
                    "status-offline", "status-busy", "status-online", "status-error");
            switch (state == null ? "" : state) {
                case "BUSY", "WARNING" -> lookupStatusLabel.getStyleClass().add("status-busy");
                case "SUCCESS", "INFO" -> lookupStatusLabel.getStyleClass().add("status-online");
                case "ERROR" -> lookupStatusLabel.getStyleClass().add("status-error");
                default -> lookupStatusLabel.getStyleClass().add("status-offline");
            }
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private void setCardState(VBox card, String stateClass) {
        card.getStyleClass().removeAll("network-summary-busy", "network-summary-success",
                "network-summary-warning", "network-summary-error");
        if (stateClass != null) {
            card.getStyleClass().add(stateClass);
        }
    }

    private void setIpInformationStatus(String state, String message) {
        ipInformationStatusLabel.setText(message);
        ipInformationStatusLabel.getStyleClass().removeAll(
                "status-offline", "status-busy", "status-online", "status-error");
        switch (state == null ? "" : state) {
            case "BUSY", "WARNING" -> ipInformationStatusLabel.getStyleClass().add("status-busy");
            case "SUCCESS", "INFO" -> ipInformationStatusLabel.getStyleClass().add("status-online");
            case "ERROR" -> ipInformationStatusLabel.getStyleClass().add("status-error");
            default -> ipInformationStatusLabel.getStyleClass().add("status-offline");
        }
    }

    private String diagnosticError(Exception exception) {
        if (exception instanceof UnknownHostException) {
            return "无法解析目标主机，请检查域名或 IP 地址";
        }
        if (exception instanceof InterruptedIOException) {
            return "检测已取消";
        }
        if (exception instanceof IllegalArgumentException) {
            return exception.getMessage();
        }
        if (exception instanceof IOException) {
            return exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "网络连接失败" : exception.getMessage();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private String modeStatus(DiagnosticMode mode) {
        return switch (mode) {
            case FULL -> "综合检测中";
            case RESOLVE_ONLY -> "解析中";
            case PORT_ONLY -> "端口检测中";
        };
    }

    private String modeDisplayName(DiagnosticMode mode) {
        return switch (mode) {
            case FULL -> "综合检测";
            case RESOLVE_ONLY -> "DNS / IP 检测";
            case PORT_ONLY -> "TCP 端口检测";
        };
    }

    private String value(String text) {
        return text == null ? "" : text.trim();
    }

    private String displayValue(String text) {
        return text == null || text.isBlank() ? "暂无数据" : text;
    }
}
