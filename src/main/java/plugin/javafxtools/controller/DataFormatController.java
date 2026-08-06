package plugin.javafxtools.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import plugin.javafxtools.base.BaseController;

import javax.xml.XMLConstants;
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
public class DataFormatController extends BaseController {

    /**
     * 格式化类型选择框。
     */
    @FXML
    private ComboBox<String> formatTypeComboBox;

    /**
     * 原始数据输入区。
     */
    @FXML
    private TextArea rawDataArea;

    /**
     * 格式化结果和模块日志输出区。
     */
    @FXML
    private TextArea formattedDataArea;

    /**
     * 执行格式化的按钮。
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
     * JSON 解析和格式化处理器。
     */
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(SerializationFeature.INDENT_OUTPUT);

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
        formatTypeComboBox.getItems().addAll("JSON", "XML");
        formatTypeComboBox.setValue("JSON");

        rawDataArea.setPromptText("粘贴 JSON 或 XML 数据");
        formattedDataArea.setPromptText("格式化结果将在这里显示");
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
            formattedDataArea.clear();
            error("JSON格式化失败: " + e.getMessage());
        } catch (Exception e) {
            formattedDataArea.clear();
            error(type + "格式化失败: " + e.getMessage());
        }
    }

    /**
     * 将纯格式化结果复制到系统剪贴板。
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
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        // 解析XML字符串
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        // 配置转换器
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
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
        setStatus("READY", "等待输入");
        rawDataArea.requestFocus();
    }

    /**
     * 页面使用独立状态标签反馈操作，避免日志污染格式化结果。
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
