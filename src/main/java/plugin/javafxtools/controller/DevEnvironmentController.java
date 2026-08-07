package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.service.DevEnvironmentService;
import plugin.javafxtools.service.DevEnvironmentService.CheckResult;
import plugin.javafxtools.service.DevEnvironmentService.EnvironmentReport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/** Controller for the on-demand development environment health check. */
public final class DevEnvironmentController extends BaseController {
    @FXML private Button inspectButton;
    @FXML private Button copyButton;
    @FXML private Label statusLabel;
    @FXML private Label availableCountLabel;
    @FXML private Label issueCountLabel;
    @FXML private Label architectureLabel;
    @FXML private TableView<CheckResult> resultTable;
    @FXML private TableColumn<CheckResult, String> statusColumn;
    @FXML private TableColumn<CheckResult, String> toolColumn;
    @FXML private TableColumn<CheckResult, String> versionColumn;
    @FXML private TableColumn<CheckResult, String> pathColumn;
    @FXML private TableColumn<CheckResult, String> detailColumn;

    private final DevEnvironmentService service = new DevEnvironmentService();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DevEnvironmentInspection");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Future<?> currentInspection;
    private EnvironmentReport currentReport;
    private boolean busy;
    private boolean cleaned;

    @FXML
    public void initialize() {
        configureTable();
        architectureLabel.setText(System.getProperty("os.name", "未知系统") + " · "
                + System.getProperty("os.arch", "未知架构"));
        inspectEnvironment();
    }

    @FXML
    private void handleInspect() {
        inspectEnvironment();
    }

    @FXML
    private void handleCopyReport() {
        if (currentReport == null) {
            setStatus("ERROR", "当前没有可复制的体检报告");
            return;
        }
        StringBuilder report = new StringBuilder("FxTools 开发环境体检\n");
        for (CheckResult result : currentReport.results()) {
            report.append('\n').append(statusText(result)).append("  ")
                    .append(result.name()).append("\n版本: ").append(result.version())
                    .append("\n路径: ").append(blankFallback(result.path(), "--"))
                    .append("\n详情: ").append(result.detail()).append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(report.toString());
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("SUCCESS", "体检报告已复制");
    }

    @Override
    public TextArea getLogArea() {
        return null;
    }

    @Override
    public void cleanup() {
        cleaned = true;
        Future<?> inspection = currentInspection;
        if (inspection != null) {
            inspection.cancel(true);
        }
        executor.shutdownNow();
        super.cleanup();
    }

    private void configureTable() {
        statusColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(statusText(cell.getValue())));
        toolColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().name()));
        versionColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().version()));
        pathColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(blankFallback(cell.getValue().path(), "--")));
        detailColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().detail()));
        resultTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        configureTooltipCells(versionColumn);
        configureTooltipCells(pathColumn);
        configureTooltipCells(detailColumn);
        resultTable.setItems(FXCollections.observableArrayList());
        resultTable.setPlaceholder(new Label("正在等待环境体检结果"));
        resultTable.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(CheckResult result, boolean empty) {
                super.updateItem(result, empty);
                getStyleClass().removeAll("environment-ok-row", "environment-warning-row",
                        "environment-error-row");
                if (!empty && result != null) {
                    getStyleClass().add(switch (result.status()) {
                        case AVAILABLE -> "environment-ok-row";
                        case WARNING, MISSING -> "environment-warning-row";
                        case ERROR -> "environment-error-row";
                    });
                }
            }
        });
    }

    private void configureTooltipCells(TableColumn<CheckResult, String> column) {
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                String text = empty ? null : value;
                setText(text);
                setTooltip(text == null || text.isBlank() ? null : new Tooltip(text));
            }
        });
    }

    private void inspectEnvironment() {
        if (busy || cleaned) {
            return;
        }
        busy = true;
        currentReport = null;
        resultTable.getItems().clear();
        resultTable.setPlaceholder(new Label("正在检查开发环境"));
        availableCountLabel.setText("--");
        issueCountLabel.setText("--");
        setStatus("BUSY", "正在并行检查开发工具");
        updateButtons();
        try {
            currentInspection = executor.submit(() -> {
                try {
                    EnvironmentReport report = service.inspect();
                    Platform.runLater(() -> finishInspection(report));
                } catch (Exception e) {
                    Platform.runLater(() -> failInspection(e));
                }
            });
        } catch (RejectedExecutionException e) {
            failInspection(e);
        }
    }

    private void finishInspection(EnvironmentReport report) {
        if (cleaned) {
            return;
        }
        busy = false;
        currentInspection = null;
        currentReport = report;
        resultTable.getItems().setAll(report.results());
        availableCountLabel.setText(report.availableCount() + " 项");
        issueCountLabel.setText(report.requiredIssueCount() + " 项");
        setStatus(report.requiredIssueCount() == 0 ? "SUCCESS" : "WARNING",
                report.requiredIssueCount() == 0 ? "核心开发环境正常" : "发现核心环境问题");
        updateButtons();
    }

    private void failInspection(Exception exception) {
        if (cleaned) {
            return;
        }
        busy = false;
        currentInspection = null;
        String message = errorMessage(exception);
        resultTable.setPlaceholder(new Label("体检失败：" + message));
        setStatus("ERROR", message);
        updateButtons();
    }

    private void updateButtons() {
        inspectButton.setDisable(busy);
        copyButton.setDisable(busy || currentReport == null);
    }

    private void setStatus(String state, String text) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().removeAll(
                "status-offline", "status-busy", "status-online", "status-error");
        statusLabel.getStyleClass().add(switch (state) {
            case "BUSY", "WARNING" -> "status-busy";
            case "SUCCESS" -> "status-online";
            case "ERROR" -> "status-error";
            default -> "status-offline";
        });
    }

    private static String statusText(CheckResult result) {
        return switch (result.status()) {
            case AVAILABLE -> "可用";
            case WARNING -> "需检查";
            case MISSING -> result.recommended() ? "需配置" : "未安装";
            case ERROR -> "异常";
        };
    }

    private static String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
