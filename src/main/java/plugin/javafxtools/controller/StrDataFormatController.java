package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import plugin.javafxtools.base.BaseController;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 字符串工具控制器
 */
public class StrDataFormatController extends BaseController {
    private static final Pattern WHITESPACE_PATTERN =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * 字符串处理类型选择框。
     */
    @FXML
    private ComboBox<String> formatTypeComboBox;

    /**
     * 原始字符串输入区。
     */
    @FXML
    private TextArea rawDataArea;

    /**
     * 处理结果和模块日志输出区。
     */
    @FXML
    private TextArea formattedDataArea;

    /**
     * 执行字符串处理的按钮。
     */
    @FXML
    private Button formatButton;

    /**
     * 清空输入和结果的按钮。
     */
    @FXML
    private Button clearButton;

    @FXML
    private Button copyButton;

    @FXML
    private Label statusLabel;

    /**
     * 获取当前模块日志输出区域。
     *
     * @return 格式化结果文本区域
     */
    @Override
    public TextArea getLogArea() {
        return null;
    }

    /**
     * 初始化方法 - 由JavaFX自动调用
     */
    @FXML
    public void initialize() {
        // 初始化格式化类型选项
        formatTypeComboBox.getItems().addAll("去除空白", "去除首尾空白", "转大写", "转小写");
        formatTypeComboBox.setValue("去除空白");

        // 设置提示文本
        rawDataArea.setPromptText("输入或粘贴需要处理的文本");
        formattedDataArea.setPromptText("处理结果将在这里显示");
        rawDataArea.textProperty().addListener((observable, oldValue, newValue) -> updateButtonStates());
        formattedDataArea.textProperty().addListener((observable, oldValue, newValue) -> updateButtonStates());
        rawDataArea.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.ENTER) {
                handleFormat();
                event.consume();
            }
        });
        updateButtonStates();
        setStatus("READY", "等待输入");
    }

    /**
     * 处理格式化按钮点击事件
     */
    @FXML
    private void handleFormat() {
        String rawData = rawDataArea.getText();
        if (rawData == null || rawData.isBlank()) {
            error("请输入要格式化的数据");
            return;
        }
        String type = formatTypeComboBox.getValue();
        try {
            String formatted;
            switch (type) {
                case "去除空白" -> {
                    formatted = WHITESPACE_PATTERN.matcher(rawData).replaceAll("");
                    info("处理完成，共 " + formatted.length() + " 个字符");
                }
                case "去除首尾空白" -> {
                    formatted = rawData.strip();
                    info("处理完成，共 " + formatted.length() + " 个字符");
                }
                case "转大写" -> {
                    formatted = rawData.toUpperCase(Locale.ROOT);
                    info("处理完成，共 " + formatted.length() + " 个字符");
                }
                case "转小写" -> {
                    formatted = rawData.toLowerCase(Locale.ROOT);
                    info("处理完成，共 " + formatted.length() + " 个字符");
                }
                case null, default -> formatted = rawData;
            }
            formattedDataArea.setText(formatted);
        } catch (Exception e) {
            formattedDataArea.clear();
            error(type + "处理失败: " + e.getMessage());
        }
    }

    /**
     * 将纯处理结果复制到系统剪贴板。
     */
    @FXML
    private void handleCopyResult() {
        String result = formattedDataArea.getText();
        if (result == null || result.isBlank()) {
            error("当前没有可复制的结果");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(result);
        Clipboard.getSystemClipboard().setContent(content);
        info("结果已复制到剪贴板");
    }

    /**
     * 处理清除按钮点击事件
     */
    @FXML
    private void handleClear() {
        rawDataArea.clear();
        formattedDataArea.clear();
        setStatus("READY", "等待输入");
        rawDataArea.requestFocus();
    }

    /**
     * 页面使用独立状态标签反馈操作，避免日志污染处理结果。
     */
    @Override
    public void log(String level, String message) {
        setStatus(level, message);
    }

    private void updateButtonStates() {
        formatButton.setDisable(rawDataArea.getText() == null || rawDataArea.getText().isBlank());
        copyButton.setDisable(formattedDataArea.getText() == null || formattedDataArea.getText().isBlank());
    }

    private void setStatus(String level, String message) {
        Runnable update = () -> {
            if (statusLabel == null) {
                return;
            }
            statusLabel.setText(message);
            statusLabel.getStyleClass().removeAll(
                    "status-text", "feedback-text", "feedback-success", "feedback-error");
            statusLabel.getStyleClass().add("feedback-text");
            if ("ERROR".equals(level)) {
                statusLabel.getStyleClass().add("feedback-error");
            } else if ("INFO".equals(level)) {
                statusLabel.getStyleClass().add("feedback-success");
            }
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }
}
