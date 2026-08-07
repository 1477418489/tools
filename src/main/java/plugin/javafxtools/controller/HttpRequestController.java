package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Pane;
import javafx.stage.FileChooser;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.control.LogViewer;
import plugin.javafxtools.model.HttpScheduleConfig;
import plugin.javafxtools.model.HttpRequestResult;
import plugin.javafxtools.model.HttpTemplate;
import plugin.javafxtools.service.HttpRequestService;
import plugin.javafxtools.service.HttpResponseFormatter;
import plugin.javafxtools.service.HttpSchedulerService;
import plugin.javafxtools.service.HttpTemplateStore;
import plugin.javafxtools.util.FxTheme;
import plugin.javafxtools.util.TimeUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * HTTP 请求页签控制器，负责 FXML 事件入口、表单装配和服务联动。
 */
public class HttpRequestController extends BaseController {
    private static final DateTimeFormatter EXPORT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    @FXML
    private TextField startTimeField;

    @FXML
    private TextField intervalField;

    @FXML
    private TextField urlField;

    @FXML
    private ComboBox<String> methodComboBox;

    @FXML
    private TextArea paramsArea;

    @FXML
    private TextArea headersArea;

    @FXML
    private LogViewer logViewer;

    @FXML
    private Button startButton;

    @FXML
    private Button sendOnceButton;

    @FXML
    private Button stopButton;

    @FXML
    private Button nowButton;

    @FXML
    private Button formatButton;

    @FXML
    private Button copyResponseButton;

    @FXML
    private Button saveResponseButton;

    @FXML
    private Button clearResponseButton;

    @FXML
    private Label responseStatusLabel;

    @FXML
    private Label responseDurationLabel;

    @FXML
    private TextArea responseBodyArea;

    @FXML
    private TextArea responseHeadersArea;

    @FXML
    private Label schedulerStatusLabel;

    @FXML
    private Pane schedulerSettingsPane;

    @FXML
    private Pane templatePane;

    @FXML
    private TabPane requestEditorPane;

    @FXML
    private ComboBox<String> responseFormatComboBox;

    @FXML
    private ComboBox<String> templateComboBox;

    @FXML
    private TextField connectTimeoutField;

    @FXML
    private TextField readTimeoutField;

    private final Map<String, HttpTemplate> templates = new LinkedHashMap<>();
    private final HttpTemplateStore templateStore = new HttpTemplateStore();
    private final HttpResponseFormatter responseFormatter = new HttpResponseFormatter();
    private final HttpRequestService requestService = new HttpRequestService();
    private HttpSchedulerService schedulerService;
    private String lastRawResponseBody;

    /**
     * 获取当前模块日志输出区域。
     *
     * @return HTTP 请求日志区域
     */
    @Override
    public TextArea getLogArea() {
        return logViewer == null ? null : logViewer.getTextArea();
    }

    /**
     * 初始化 HTTP 请求页签。
     */
    @FXML
    public void initialize() {
        logViewer.setOnClear(this::handleClearLog);
        try {
            schedulerService = new HttpSchedulerService(requestService,
                    this::info, this::debug, this::error,
                    this::acceptResponse,
                    this::setRunningState, this::setStoppedState);
            setupInitialUi();
            loadTemplates();
            updateTemplateComboBox();
            info("HTTP请求模块初始化完成");
        } catch (RuntimeException e) {
            error("HTTP控制器初始化失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 现在按钮：快速设置当前时间为开始时间。
     */
    @FXML
    private void handleNowButton() {
        startTimeField.setText(TimeUtils.getCurrentDateTime());
        info("已设置开始时间为当前时间");
    }

    /**
     * 响应美化按钮：仅对最近响应体 JSON 进行格式化。
     */
    @FXML
    private void handleFormatButton() {
        if (lastRawResponseBody == null || lastRawResponseBody.isEmpty()) {
            info("无内容可格式化");
            return;
        }
        String formatted = responseFormatter.tryPrettyJson(lastRawResponseBody);
        if (formatted != null) {
            responseBodyArea.setText(formatted);
            info("响应正文已格式化");
        } else {
            info("不是合法的JSON，无法美化");
        }
    }

    @FXML
    private void handleCopyResponse() {
        String text = responseBodyArea.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        info("响应正文已复制");
    }

    @FXML
    private void handleSaveResponse() {
        String text = responseBodyArea.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存 HTTP 响应");
        chooser.setInitialFileName("http-response-"
                + EXPORT_TIME_FORMAT.format(LocalDateTime.now()) + ".txt");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"));
        File target = chooser.showSaveDialog(responseBodyArea.getScene().getWindow());
        if (target == null) {
            return;
        }
        try {
            Files.writeString(target.toPath(), text, StandardCharsets.UTF_8);
            info("响应正文已保存: " + target.getAbsolutePath());
        } catch (IOException e) {
            error("保存响应失败: " + e.getMessage());
        }
    }

    @FXML
    private void handleClearResponse() {
        lastRawResponseBody = null;
        responseBodyArea.clear();
        responseHeadersArea.clear();
        responseStatusLabel.setText("等待响应");
        responseDurationLabel.setText("-- ms");
        responseStatusLabel.getStyleClass().removeAll(
                "status-online", "status-busy", "status-error");
        responseStatusLabel.getStyleClass().add("status-offline");
        updateResponseActions();
    }

    /**
     * 保存模板按钮。
     */
    @FXML
    private void handleSaveTemplate() {
        String templateName = templateComboBox.getEditor().getText().trim();
        if (templateName.isEmpty()) {
            error("请输入模板名称");
            return;
        }
        if (templates.containsKey(templateName) && !confirmTemplateOverwrite(templateName)) {
            return;
        }
        Map<String, HttpTemplate> updatedTemplates = new LinkedHashMap<>(templates);
        updatedTemplates.put(templateName, buildTemplateFromUi());
        if (!saveTemplates(updatedTemplates)) {
            return;
        }
        templates.clear();
        templates.putAll(updatedTemplates);
        updateTemplateComboBox();
        templateComboBox.setValue(templateName);
        info("已保存模板: " + templateName);
    }

    /**
     * 加载模板按钮。
     */
    @FXML
    private void handleLoadTemplate() {
        String templateName = templateComboBox.getValue();
        if (templateName == null || !templates.containsKey(templateName)) {
            error("请选择要加载的模板");
            return;
        }
        applyTemplateToUi(templates.get(templateName));
        info("已载入模板: " + templateName);
    }

    /**
     * 删除模板按钮。
     */
    @FXML
    private void handleDeleteTemplate() {
        String templateName = templateComboBox.getValue();
        if (templateName == null || !templates.containsKey(templateName)) {
            error("请选择要删除的模板");
            return;
        }
        if (!confirmTemplateDelete(templateName)) {
            return;
        }
        Map<String, HttpTemplate> updatedTemplates = new LinkedHashMap<>(templates);
        updatedTemplates.remove(templateName);
        if (!saveTemplates(updatedTemplates)) {
            return;
        }
        templates.clear();
        templates.putAll(updatedTemplates);
        updateTemplateComboBox();
        templateComboBox.getSelectionModel().clearSelection();
        templateComboBox.getEditor().clear();
        info("已删除模板: " + templateName);
    }

    /**
     * 开始调度按钮。
     */
    @FXML
    private void handleStartButton() {
        schedulerService.start(buildScheduleConfig());
    }

    /**
     * 立即发送一次当前请求。
     */
    @FXML
    private void handleSendOnceButton() {
        schedulerService.executeOnce(buildScheduleConfig());
    }

    /**
     * 停止调度按钮。
     */
    @FXML
    private void handleStopButton() {
        schedulerService.stop();
        info("请求任务已停止");
    }

    /**
     * 资源清理。
     */
    public void cleanup() {
        if (schedulerService != null) {
            schedulerService.stop();
        }
        info("HTTP请求模块资源已清理");
        super.cleanup();
    }

    private void setupInitialUi() {
        stopButton.setDisable(true);
        formatButton.setDisable(true);
        copyResponseButton.setDisable(true);
        saveResponseButton.setDisable(true);
        clearResponseButton.setDisable(true);
        responseBodyArea.setEditable(false);
        responseHeadersArea.setEditable(false);
        methodComboBox.getItems().addAll("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
        methodComboBox.setValue("GET");
        responseFormatComboBox.getItems().addAll("Auto", "Pretty JSON", "Raw");
        responseFormatComboBox.setValue("Auto");
        startTimeField.setText(TimeUtils.getCurrentDateTime());
        intervalField.setText("10");
        connectTimeoutField.setText("5000");
        readTimeoutField.setText("10000");
        paramsArea.setPromptText("输入查询参数或请求体");
        headersArea.setPromptText("每行一个请求头，例如 Content-Type: application/json");
        intervalField.setTextFormatter(digitsOnlyFormatter());
        connectTimeoutField.setTextFormatter(digitsOnlyFormatter());
        readTimeoutField.setTextFormatter(digitsOnlyFormatter());
        updateSchedulerStatus(false);
    }

    private HttpScheduleConfig buildScheduleConfig() {
        return new HttpScheduleConfig(
                text(startTimeField),
                text(intervalField),
                text(urlField),
                methodComboBox.getValue(),
                paramsArea.getText(),
                headersArea.getText(),
                text(connectTimeoutField),
                text(readTimeoutField),
                responseFormatComboBox.getValue()
        );
    }

    private HttpTemplate buildTemplateFromUi() {
        return new HttpTemplate(
                urlField.getText(),
                methodComboBox.getValue(),
                paramsArea.getText(),
                headersArea.getText(),
                intervalField.getText(),
                connectTimeoutField.getText(),
                readTimeoutField.getText()
        );
    }

    private void applyTemplateToUi(HttpTemplate template) {
        urlField.setText(template.url);
        methodComboBox.setValue(template.method);
        paramsArea.setText(template.params);
        headersArea.setText(template.headers);
        intervalField.setText(template.interval);
        connectTimeoutField.setText(template.connectTimeout);
        readTimeoutField.setText(template.readTimeout);
    }

    private void loadTemplates() {
        try {
            templates.clear();
            templates.putAll(templateStore.loadTemplates());
        } catch (Exception e) {
            error(e.getMessage());
        }
    }

    private boolean saveTemplates(Map<String, HttpTemplate> updatedTemplates) {
        try {
            templateStore.saveTemplates(updatedTemplates);
            return true;
        } catch (Exception e) {
            error(e.getMessage());
            return false;
        }
    }

    private void updateTemplateComboBox() {
        Runnable update = () -> templateComboBox.getItems().setAll(
                templates.keySet().stream().sorted().toList());
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private void setRunningState() {
        startButton.setDisable(true);
        sendOnceButton.setDisable(true);
        stopButton.setDisable(false);
        setConfigurationDisabled(true);
        updateSchedulerStatus(true);
    }

    private void setStoppedState() {
        startButton.setDisable(false);
        sendOnceButton.setDisable(false);
        stopButton.setDisable(true);
        setConfigurationDisabled(false);
        updateSchedulerStatus(false);
    }

    private String text(TextField field) {
        String value = field.getText();
        return value == null ? "" : value.trim();
    }

    private void acceptResponse(HttpRequestResult result) {
        lastRawResponseBody = result.rawBody();
        String displayBody = responseFormatter.formatForPreview(
                result.rawBody(), responseFormatComboBox.getValue());
        responseBodyArea.setText(displayBody == null || displayBody.isEmpty()
                ? "[响应体为空]" : displayBody);
        responseHeadersArea.setText(result.responseHeaders());
        responseStatusLabel.setText("HTTP " + result.statusCode());
        responseDurationLabel.setText(result.elapsedMillis() + " ms");
        responseStatusLabel.getStyleClass().removeAll(
                "status-offline", "status-online", "status-busy", "status-error");
        responseStatusLabel.getStyleClass().add(result.statusCode() >= 400
                ? "status-error" : "status-online");
        updateResponseActions();
    }

    private void updateResponseActions() {
        boolean empty = lastRawResponseBody == null || lastRawResponseBody.isEmpty();
        formatButton.setDisable(empty);
        copyResponseButton.setDisable(empty);
        saveResponseButton.setDisable(empty);
        clearResponseButton.setDisable(lastRawResponseBody == null);
    }

    private void setConfigurationDisabled(boolean disabled) {
        methodComboBox.setDisable(disabled);
        urlField.setDisable(disabled);
        schedulerSettingsPane.setDisable(disabled);
        templatePane.setDisable(disabled);
        requestEditorPane.setDisable(disabled);
    }

    private void updateSchedulerStatus(boolean running) {
        schedulerStatusLabel.setText(running ? "调度中" : "已停止");
        schedulerStatusLabel.getStyleClass().removeAll("status-offline", "status-online", "status-busy");
        schedulerStatusLabel.getStyleClass().add(running ? "status-online" : "status-offline");
    }

    private TextFormatter<String> digitsOnlyFormatter() {
        UnaryOperator<TextFormatter.Change> filter = change ->
                change.getControlNewText().matches("\\d*") ? change : null;
        return new TextFormatter<>(filter);
    }

    private boolean confirmTemplateOverwrite(String templateName) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "模板 \"" + templateName + "\" 已存在，是否覆盖？", ButtonType.OK, ButtonType.CANCEL);
        FxTheme.apply(alert);
        alert.setTitle("覆盖模板");
        alert.setHeaderText(null);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private boolean confirmTemplateDelete(String templateName) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除模板 \"" + templateName + "\"？", ButtonType.OK, ButtonType.CANCEL);
        FxTheme.apply(alert);
        alert.setTitle("删除模板");
        alert.setHeaderText(null);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }
}
