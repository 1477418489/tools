package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.service.Base64CodecService;
import plugin.javafxtools.service.Base64CodecService.TextEncoding;
import plugin.javafxtools.service.Base64CodecService.Variant;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Text Base64 encoding and decoding workspace. */
public final class Base64Controller extends BaseController {
    @FXML private ComboBox<Variant> variantComboBox;
    @FXML private ComboBox<TextEncoding> encodingComboBox;
    @FXML private Button encodeButton;
    @FXML private Button decodeButton;
    @FXML private Button swapButton;
    @FXML private Button copyButton;
    @FXML private Button clearButton;
    @FXML private Label statusLabel;
    @FXML private Label inputCountLabel;
    @FXML private Label outputCountLabel;
    @FXML private TextArea inputArea;
    @FXML private TextArea outputArea;

    private final Base64CodecService service = new Base64CodecService();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Base64Codec");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong operationGeneration = new AtomicLong();
    private volatile Future<?> currentOperation;
    private boolean busy;
    private boolean cleaned;

    @FXML
    public void initialize() {
        variantComboBox.getItems().setAll(Variant.values());
        variantComboBox.getSelectionModel().select(Variant.STANDARD);
        encodingComboBox.getItems().setAll(TextEncoding.values());
        encodingComboBox.getSelectionModel().select(TextEncoding.UTF_8);
        inputArea.setTextFormatter(lengthLimiter());
        inputArea.textProperty().addListener((observable, oldValue, newValue) -> updateState());
        outputArea.textProperty().addListener((observable, oldValue, newValue) -> updateState());
        setStatus("READY", "就绪");
        updateState();
    }

    @FXML
    private void handleEncode() {
        convert(true);
    }

    @FXML
    private void handleDecode() {
        convert(false);
    }

    @FXML
    private void handleSwap() {
        if (busy) {
            return;
        }
        String output = outputArea.getText();
        inputArea.setText(output == null ? "" : output);
        outputArea.clear();
        inputArea.requestFocus();
        setStatus("READY", "结果已移到输入区");
    }

    @FXML
    private void handlePaste() {
        if (busy) {
            return;
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) {
            setStatus("ERROR", "剪贴板中没有文本");
            return;
        }
        String value = clipboard.getString();
        if (value.length() > Base64CodecService.MAX_INPUT_CHARACTERS) {
            setStatus("ERROR", "剪贴板文本超过输入上限");
            return;
        }
        inputArea.setText(value);
        setStatus("READY", "已从剪贴板粘贴");
    }

    @FXML
    private void handleCopy() {
        String output = outputArea.getText();
        if (output == null || output.isEmpty()) {
            setStatus("ERROR", "当前没有可复制的结果");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(output);
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("SUCCESS", "结果已复制");
    }

    @FXML
    private void handleClear() {
        operationGeneration.incrementAndGet();
        Future<?> operation = currentOperation;
        if (operation != null) {
            operation.cancel(true);
        }
        currentOperation = null;
        busy = false;
        inputArea.clear();
        outputArea.clear();
        setStatus("READY", "已清空");
        inputArea.requestFocus();
        updateState();
    }

    @Override
    public TextArea getLogArea() {
        return null;
    }

    @Override
    public void cleanup() {
        cleaned = true;
        operationGeneration.incrementAndGet();
        Future<?> operation = currentOperation;
        if (operation != null) {
            operation.cancel(true);
        }
        executor.shutdownNow();
        super.cleanup();
    }

    private void convert(boolean encode) {
        if (busy || cleaned) {
            return;
        }
        String input = inputArea.getText();
        if (input == null || input.isEmpty()) {
            setStatus("ERROR", "请输入要处理的文本");
            inputArea.requestFocus();
            return;
        }
        Variant variant = variantComboBox.getValue() == null
                ? Variant.STANDARD : variantComboBox.getValue();
        TextEncoding encoding = encodingComboBox.getValue() == null
                ? TextEncoding.UTF_8 : encodingComboBox.getValue();
        long operationId = operationGeneration.incrementAndGet();
        busy = true;
        setStatus("BUSY", encode ? "正在编码" : "正在解码");
        updateState();
        try {
            currentOperation = executor.submit(() -> {
                try {
                    String result = encode ? service.encode(input, variant, encoding)
                            : service.decode(input, variant, encoding);
                    Platform.runLater(() -> finishConversion(operationId, result,
                            encode ? "编码完成" : "解码完成"));
                } catch (Exception e) {
                    Platform.runLater(() -> failConversion(operationId, e));
                }
            });
        } catch (RejectedExecutionException e) {
            failConversion(operationId, e);
        }
    }

    private void finishConversion(long operationId, String result, String message) {
        if (cleaned || operationGeneration.get() != operationId) {
            return;
        }
        busy = false;
        currentOperation = null;
        outputArea.setText(result);
        setStatus("SUCCESS", message);
        updateState();
    }

    private void failConversion(long operationId, Exception exception) {
        if (cleaned || operationGeneration.get() != operationId) {
            return;
        }
        busy = false;
        currentOperation = null;
        outputArea.clear();
        setStatus("ERROR", errorMessage(exception));
        updateState();
    }

    private TextFormatter<String> lengthLimiter() {
        return new TextFormatter<>(change -> change.getControlNewText().length()
                <= Base64CodecService.MAX_INPUT_CHARACTERS ? change : null);
    }

    private void updateState() {
        int inputLength = inputArea.getText() == null ? 0 : inputArea.getText().length();
        int outputLength = outputArea.getText() == null ? 0 : outputArea.getText().length();
        inputCountLabel.setText(String.format("%,d 字符", inputLength));
        outputCountLabel.setText(String.format("%,d 字符", outputLength));
        boolean hasInput = inputLength > 0;
        encodeButton.setDisable(busy || !hasInput);
        decodeButton.setDisable(busy || !hasInput);
        variantComboBox.setDisable(busy);
        encodingComboBox.setDisable(busy);
        swapButton.setDisable(busy || outputLength == 0
                || outputLength > Base64CodecService.MAX_INPUT_CHARACTERS);
        copyButton.setDisable(busy || outputLength == 0);
        clearButton.setDisable(busy ? false : inputLength == 0 && outputLength == 0);
    }

    private void setStatus(String state, String text) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().removeAll(
                "status-offline", "status-busy", "status-online", "status-error");
        statusLabel.getStyleClass().add(switch (state) {
            case "BUSY" -> "status-busy";
            case "SUCCESS" -> "status-online";
            case "ERROR" -> "status-error";
            default -> "status-offline";
        });
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
