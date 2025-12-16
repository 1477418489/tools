package plugin.javafxtools.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.util.TimeUtils;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * 数据格式化工具控制器 - 提供JSON/XML格式化功能
 */
public class DataFormatController implements ModuleLogger {

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

    private final ObjectMapper jsonMapper = new ObjectMapper(); // JSON处理器


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
                formattedDataArea.appendText(formattedMessage );
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
        formatTypeComboBox.getItems().addAll("JSON", "XML");
        formatTypeComboBox.setValue("JSON");

        // 配置JSON美化输出
        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 设置提示文本
        rawDataArea.setPromptText("在此输入要格式化的JSON或XML数据...");
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
            if ("JSON".equals(type)) {
                formatted = formatJson(rawData);
                info("JSON格式化成功");
            } else {
                formatted = formatXml(rawData);
                info("XML格式化成功");
            }
            formattedDataArea.setText(formatted);
        } catch (JsonProcessingException e) {
            formattedDataArea.setText("JSON格式错误: " + e.getMessage());
            error("JSON格式化失败: " + e.getMessage());
        } catch (Exception e) {
            formattedDataArea.setText(type + "格式化错误: " + e.getMessage());
            error(type + "格式化失败: " + e.getMessage());
        }
    }

    /**
     * 格式化JSON数据
     *
     * @param json 原始JSON字符串
     * @return 格式化后的JSON字符串
     * @throws JsonProcessingException 如果JSON解析失败
     */
    private String formatJson(String json) throws JsonProcessingException {
        // 解析并重新序列化以实现格式化
        Object jsonObject = jsonMapper.readValue(json, Object.class);
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
    }

    /**
     * 格式化XML数据
     *
     * @param xml 原始XML字符串
     * @return 格式化后的XML字符串
     * @throws Exception 如果XML解析或转换失败
     */
    private String formatXml(String xml) throws Exception {
        // 创建文档构建器工厂（禁用外部实体引用防止XXE攻击）
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        // 解析XML字符串
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        // 配置转换器
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

        // 执行格式化转换
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
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