package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.util.TimeUtils;

/**
 * 字符串工具控制器
 */
public class StrDataFormatController implements ModuleLogger {

    @FXML
    private ComboBox<String> formatTypeComboBox; // 格式化类型选择框
    @FXML
    private TextArea rawDataArea;               // 原始数据输入区
    @FXML
    private TextArea formattedDataArea;         // 格式化结果区
    @FXML
    private Button formatButton;                // 格式化按钮
    @FXML
    private Button clearButton;                 // 清除按钮


    public TextArea getLogArea() {
        return formattedDataArea;
    }

    /**
     * 自定义日志方法 - 只输出到本模块日志区
     */
    @Override
    public void log(String level, String message) {
        String formattedMessage = String.format("\n"+"[%s][%s] %s",
                TimeUtils.getCurrentDateTime(), level, message);

        Platform.runLater(() -> {
            if (formattedDataArea != null && formattedDataArea.getScene() != null) {
                formattedDataArea.appendText(formattedMessage);
                formattedDataArea.setScrollTop(Double.MAX_VALUE); // 自动滚动到底部
            }
        });
    }

    /**
     * 初始化方法 - 由JavaFX自动调用
     */
    @FXML
    public void initialize() {
        // 初始化格式化类型选项
        formatTypeComboBox.getItems().addAll("普通", "转大写", "转小写");
        formatTypeComboBox.setValue("普通");

        // 设置提示文本
        rawDataArea.setPromptText("在此输入要格式化的数据...");
        formattedDataArea.setPromptText("格式化结果将显示在这里...");
        info("数据格式化工具控制器模块初始化完成");
    }

    /**
     * 处理格式化按钮点击事件
     */
    @FXML
    private void handleFormat() {
        String rawData = rawDataArea.getText().trim();
        if (rawData.isEmpty()) {
            error("请输入要格式化的数据");
            return;
        }
        String type = formatTypeComboBox.getValue();
        try {
            String formatted;
            switch (type) {
                case "普通" -> {
                    // 去除多余空格
                    formatted = rawData.replaceAll("\\s+", "");
                    info("格式化成功,数量:" + formatted.length());
                }
                case "转大写" -> {
                    formatted = rawData.toUpperCase();
                    info("格式化成功,数量:" + formatted.length());
                }
                case "转小写" -> {
                    formatted = rawData.toLowerCase();
                    info("格式化成功,数量:" + formatted.length());
                }
                case null, default -> formatted = rawData;
            }
            formattedDataArea.setText(formatted);
        } catch (Exception e) {
            formattedDataArea.setText(type + "格式化错误: " + e.getMessage());
            error(type + "格式化失败: " + e.getMessage());
        }
    }


    /**
     * 处理清除按钮点击事件
     */
    @FXML
    private void handleClear() {
        rawDataArea.clear();
        formattedDataArea.clear();
        info("已清除输入和格式化结果");
    }
    /**
     * 清空日志按钮
     */
    @FXML
    private void handleClearLog() {
        Platform.runLater(() -> {
            if (formattedDataArea != null) {
                formattedDataArea.clear();
            }
        });
    }
    /**
     * 清理资源
     */
    public void cleanup() {
    }
}