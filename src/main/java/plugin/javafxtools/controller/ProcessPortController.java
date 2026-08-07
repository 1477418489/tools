package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.service.ProcessPortService;
import plugin.javafxtools.service.ProcessPortService.PortEntry;
import plugin.javafxtools.service.ProcessPortService.ProcessEntry;
import plugin.javafxtools.service.ProcessPortService.Snapshot;
import plugin.javafxtools.util.FxTheme;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** On-demand Windows process and TCP listener workspace. */
public final class ProcessPortController extends BaseController {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @FXML private TextField filterField;
    @FXML private Button refreshButton;
    @FXML private Button terminateButton;
    @FXML private Button copyButton;
    @FXML private Label statusLabel;
    @FXML private Label processCountLabel;
    @FXML private Label memoryTotalLabel;
    @FXML private Label listenerCountLabel;
    @FXML private Label capturedAtLabel;
    @FXML private TabPane resultTabPane;
    @FXML private TableView<ProcessEntry> processTable;
    @FXML private TableColumn<ProcessEntry, String> processNameColumn;
    @FXML private TableColumn<ProcessEntry, String> processPidColumn;
    @FXML private TableColumn<ProcessEntry, String> processMemoryColumn;
    @FXML private TableColumn<ProcessEntry, String> processCpuColumn;
    @FXML private TableColumn<ProcessEntry, String> processPortsColumn;
    @FXML private TableColumn<ProcessEntry, String> processUserColumn;
    @FXML private TableColumn<ProcessEntry, String> processCommandColumn;
    @FXML private TableView<PortEntry> portTable;
    @FXML private TableColumn<PortEntry, String> portNumberColumn;
    @FXML private TableColumn<PortEntry, String> portAddressColumn;
    @FXML private TableColumn<PortEntry, String> portPidColumn;
    @FXML private TableColumn<PortEntry, String> portProcessColumn;

    private final ProcessPortService service = new ProcessPortService();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ProcessPortSnapshot");
        thread.setDaemon(true);
        return thread;
    });
    private final ObservableList<ProcessEntry> processes = FXCollections.observableArrayList();
    private final ObservableList<PortEntry> ports = FXCollections.observableArrayList();
    private final FilteredList<ProcessEntry> filteredProcesses = new FilteredList<>(processes);
    private final FilteredList<PortEntry> filteredPorts = new FilteredList<>(ports);

    private volatile Future<?> currentOperation;
    private boolean busy;
    private boolean cleaned;

    @FXML
    public void initialize() {
        configureTables();
        processTable.setItems(filteredProcesses);
        portTable.setItems(filteredPorts);
        filterField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        processTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> {
                    if (selected != null) {
                        portTable.getSelectionModel().clearSelection();
                    }
                    updateButtons();
                });
        portTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> {
                    if (selected != null) {
                        processTable.getSelectionModel().clearSelection();
                    }
                    updateButtons();
                });
        resultTabPane.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> updateButtons());
        if (service.isSupported()) {
            refreshSnapshot();
        } else {
            setStatus("ERROR", "仅支持 Windows");
            updateButtons();
        }
    }

    @FXML
    private void handleRefresh() {
        refreshSnapshot();
    }

    @FXML
    private void handleClearFilter() {
        filterField.clear();
        filterField.requestFocus();
    }

    @FXML
    private void handleCopySelected() {
        String detail = selectedDetail();
        if (detail == null) {
            setStatus("ERROR", "请先选择进程或监听端口");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(detail);
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("SUCCESS", "所选详情已复制");
    }

    @FXML
    private void handleTerminateSelected() {
        SelectedProcess selected = selectedProcess();
        if (selected == null) {
            setStatus("ERROR", "请先选择要终止的进程");
            return;
        }
        if (!confirmTermination(selected)) {
            return;
        }
        setStatus("BUSY", "正在终止 PID " + selected.pid());
        runOperation(() -> {
            service.terminateProcess(selected.pid(), selected.name());
            Snapshot snapshot = service.capture();
            Platform.runLater(() -> finishSnapshot(snapshot,
                    "已终止 " + selected.name() + "（PID " + selected.pid() + "）"));
        }, "终止进程失败");
    }

    @Override
    public TextArea getLogArea() {
        return null;
    }

    @Override
    public void cleanup() {
        cleaned = true;
        Future<?> operation = currentOperation;
        if (operation != null) {
            operation.cancel(true);
        }
        executor.shutdownNow();
        super.cleanup();
    }

    private void configureTables() {
        processNameColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().name()));
        processPidColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(Long.toString(cell.getValue().pid())));
        processMemoryColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(formatBytes(cell.getValue().memoryBytes())));
        processCpuColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(formatDuration(cell.getValue().cpuSeconds())));
        processPortsColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().ports().isEmpty() ? "--"
                        : cell.getValue().ports().toString()
                        .replace("[", "").replace("]", "")));
        processUserColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(blankFallback(cell.getValue().user(), "--")));
        processCommandColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(blankFallback(cell.getValue().command(), "--")));
        portNumberColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(Integer.toString(cell.getValue().port())));
        portAddressColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().address()));
        portPidColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(Long.toString(cell.getValue().pid())));
        portProcessColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().processName()));
        processTable.setPlaceholder(new Label("没有匹配的进程"));
        portTable.setPlaceholder(new Label("没有匹配的 TCP 监听端口"));
    }

    private void refreshSnapshot() {
        if (busy || cleaned || !service.isSupported()) {
            return;
        }
        setStatus("BUSY", "正在读取系统快照");
        runOperation(() -> {
            Snapshot snapshot = service.capture();
            Platform.runLater(() -> finishSnapshot(snapshot, "快照已刷新"));
        }, "读取进程与端口失败");
    }

    private void runOperation(CheckedOperation operation, String errorTitle) {
        busy = true;
        updateButtons();
        try {
            currentOperation = executor.submit(() -> {
                try {
                    operation.run();
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        if (cleaned) {
                            return;
                        }
                        busy = false;
                        currentOperation = null;
                        setStatus("ERROR", errorMessage(e));
                        updateButtons();
                        showError(errorTitle, errorMessage(e));
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            busy = false;
            currentOperation = null;
            setStatus("ERROR", "进程服务已关闭");
            updateButtons();
        }
    }

    private void finishSnapshot(Snapshot snapshot, String message) {
        if (cleaned) {
            return;
        }
        busy = false;
        currentOperation = null;
        processes.setAll(snapshot.processes());
        ports.setAll(snapshot.ports());
        applyFilter();
        long memoryTotal = snapshot.processes().stream()
                .mapToLong(ProcessEntry::memoryBytes).filter(value -> value >= 0).sum();
        boolean memoryAvailable = snapshot.processes().stream()
                .anyMatch(process -> process.memoryBytes() >= 0);
        processCountLabel.setText(snapshot.processes().size() + " 个");
        memoryTotalLabel.setText(memoryAvailable ? formatBytes(memoryTotal) : "--");
        listenerCountLabel.setText(snapshot.ports().size() + " 个");
        capturedAtLabel.setText(TIME_FORMAT.format(snapshot.capturedAt()));
        statusLabel.setTooltip(snapshot.warnings().isEmpty() ? null
                : new Tooltip(String.join("\n", snapshot.warnings())));
        setStatus(snapshot.warnings().isEmpty() ? "SUCCESS" : "WARNING",
                snapshot.warnings().isEmpty() ? message : message + " · 部分数据受限");
        updateButtons();
    }

    private void applyFilter() {
        String query = filterField.getText() == null ? ""
                : filterField.getText().strip().toLowerCase(Locale.ROOT);
        filteredProcesses.setPredicate(process -> query.isEmpty()
                || contains(process.name(), query)
                || contains(Long.toString(process.pid()), query)
                || contains(process.user(), query)
                || contains(process.command(), query)
                || process.ports().stream().anyMatch(port -> contains(port.toString(), query)));
        filteredPorts.setPredicate(port -> query.isEmpty()
                || contains(Integer.toString(port.port()), query)
                || contains(port.address(), query)
                || contains(Long.toString(port.pid()), query)
                || contains(port.processName(), query));
    }

    private SelectedProcess selectedProcess() {
        if (isProcessTabActive()) {
            ProcessEntry process = processTable.getSelectionModel().getSelectedItem();
            return process == null ? null : new SelectedProcess(process.pid(), process.name());
        }
        PortEntry port = portTable.getSelectionModel().getSelectedItem();
        return port == null ? null : new SelectedProcess(port.pid(), port.processName());
    }

    private String selectedDetail() {
        if (isProcessTabActive()) {
            ProcessEntry process = processTable.getSelectionModel().getSelectedItem();
            if (process == null) {
                return null;
            }
            return "进程: " + process.name() + "\nPID: " + process.pid()
                    + "\n内存: " + formatBytes(process.memoryBytes())
                    + "\nCPU 时间: " + formatDuration(process.cpuSeconds())
                    + "\n监听端口: " + (process.ports().isEmpty() ? "无" : process.ports())
                    + "\n用户: " + blankFallback(process.user(), "未知")
                    + "\n命令: " + blankFallback(process.command(), "未知");
        }
        PortEntry port = portTable.getSelectionModel().getSelectedItem();
        return port == null ? null : "监听端口: " + port.address() + ":" + port.port()
                + "\n协议: " + port.protocol() + "\n进程: " + port.processName()
                + "\nPID: " + port.pid();
    }

    private boolean isProcessTabActive() {
        return resultTabPane.getSelectionModel().getSelectedIndex() == 0;
    }

    private boolean confirmTermination(SelectedProcess selected) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "将强制终止 " + selected.name() + "（PID " + selected.pid()
                        + "）及其子进程，未保存的数据可能丢失。",
                ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle("确认终止进程");
        alert.setHeaderText("终止所选进程？");
        if (terminateButton.getScene() != null) {
            alert.initOwner(terminateButton.getScene().getWindow());
        }
        FxTheme.apply(alert);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        if (terminateButton.getScene() != null) {
            alert.initOwner(terminateButton.getScene().getWindow());
        }
        FxTheme.apply(alert);
        alert.show();
    }

    private void updateButtons() {
        boolean supported = service.isSupported();
        refreshButton.setDisable(busy || !supported);
        copyButton.setDisable(busy || selectedProcess() == null);
        SelectedProcess selected = selectedProcess();
        boolean protectedProcess = selected != null
                && (selected.pid() <= 4 || selected.pid() == ProcessHandle.current().pid());
        boolean unknownProcess = selected != null
                && (selected.name().equals("未知进程") || selected.name().startsWith("PID "));
        terminateButton.setDisable(busy || !supported || selected == null
                || protectedProcess || unknownProcess);
    }

    private void setStatus(String state, String text) {
        statusLabel.setText(text);
        if (!state.equals("WARNING")) {
            statusLabel.setTooltip(null);
        }
        statusLabel.getStyleClass().removeAll(
                "status-offline", "status-busy", "status-online", "status-error");
        statusLabel.getStyleClass().add(switch (state) {
            case "BUSY", "WARNING" -> "status-busy";
            case "SUCCESS" -> "status-online";
            case "ERROR" -> "status-error";
            default -> "status-offline";
        });
    }

    static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "--";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s",
                value, units[unit]);
    }

    private static String formatDuration(long seconds) {
        if (seconds < 0) {
            return "--";
        }
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long remaining = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, remaining);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }

    private record SelectedProcess(long pid, String name) {
    }
}
