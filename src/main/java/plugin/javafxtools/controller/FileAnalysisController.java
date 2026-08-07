package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.service.FileAnalysisService;
import plugin.javafxtools.service.FileAnalysisService.FileAnalysis;

import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Controller for file hashing, encoding detection and lock hints. */
public final class FileAnalysisController extends BaseController {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    @FXML private TextField filePathField;
    @FXML private Button selectButton;
    @FXML private Button analyzeButton;
    @FXML private Button cancelButton;
    @FXML private Button copySha256Button;
    @FXML private Button copySha1Button;
    @FXML private Button copyMd5Button;
    @FXML private Label statusLabel;
    @FXML private Label fileNameLabel;
    @FXML private Label fileSizeLabel;
    @FXML private Label fileTypeLabel;
    @FXML private Label encodingLabel;
    @FXML private Label modifiedLabel;
    @FXML private Label accessLabel;
    @FXML private Label lockStatusLabel;
    @FXML private Label lockDetailLabel;
    @FXML private Label encodingDetailLabel;
    @FXML private TextField sha256Field;
    @FXML private TextField sha1Field;
    @FXML private TextField md5Field;

    private final FileAnalysisService service = new FileAnalysisService();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FileAnalysis");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong analysisGeneration = new AtomicLong();
    private volatile Future<?> currentAnalysis;
    private File selectedFile;
    private boolean busy;
    private boolean cleaned;

    @FXML
    public void initialize() {
        resetResult();
        setStatus("READY", "请选择文件");
        updateButtons();
    }

    @FXML
    private void handleSelectFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要分析的文件");
        if (selectedFile != null && selectedFile.getParentFile() != null
                && selectedFile.getParentFile().isDirectory()) {
            chooser.setInitialDirectory(selectedFile.getParentFile());
        }
        File selected = chooser.showOpenDialog(filePathField.getScene().getWindow());
        if (selected == null) {
            return;
        }
        selectedFile = selected;
        filePathField.setText(selected.getAbsolutePath());
        analyzeSelectedFile();
    }

    @FXML
    private void handleAnalyze() {
        analyzeSelectedFile();
    }

    @FXML
    private void handleCancel() {
        Future<?> analysis = currentAnalysis;
        if (analysis == null || !busy) {
            return;
        }
        analysisGeneration.incrementAndGet();
        analysis.cancel(true);
        currentAnalysis = null;
        busy = false;
        setStatus("READY", "分析已取消");
        updateButtons();
    }

    @FXML
    private void handleCopySha256() {
        copyHash(sha256Field, "SHA-256");
    }

    @FXML
    private void handleCopySha1() {
        copyHash(sha1Field, "SHA-1");
    }

    @FXML
    private void handleCopyMd5() {
        copyHash(md5Field, "MD5");
    }

    @Override
    public TextArea getLogArea() {
        return null;
    }

    @Override
    public void cleanup() {
        cleaned = true;
        analysisGeneration.incrementAndGet();
        Future<?> analysis = currentAnalysis;
        if (analysis != null) {
            analysis.cancel(true);
        }
        executor.shutdownNow();
        super.cleanup();
    }

    private void analyzeSelectedFile() {
        if (busy || cleaned || selectedFile == null) {
            return;
        }
        busy = true;
        long analysisId = analysisGeneration.incrementAndGet();
        resetResult();
        fileNameLabel.setText(selectedFile.getName());
        setStatus("BUSY", "正在计算哈希并分析文件");
        updateButtons();
        try {
            currentAnalysis = executor.submit(() -> {
                try {
                    FileAnalysis result = service.analyze(selectedFile.toPath());
                    Platform.runLater(() -> applyResult(analysisId, result));
                } catch (Exception e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        Platform.runLater(() -> failAnalysis(analysisId, e));
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            failAnalysis(analysisId, e);
        }
    }

    private void applyResult(long analysisId, FileAnalysis result) {
        if (cleaned || analysisGeneration.get() != analysisId) {
            return;
        }
        busy = false;
        currentAnalysis = null;
        fileNameLabel.setText(result.path().getFileName().toString());
        fileSizeLabel.setText(formatBytes(result.size()));
        fileTypeLabel.setText(result.contentType());
        encodingLabel.setText(result.encoding());
        modifiedLabel.setText(TIME_FORMAT.format(result.modifiedAt()));
        accessLabel.setText((result.readable() ? "可读" : "不可读") + " · "
                + (result.writable() ? "可写" : "只读"));
        encodingDetailLabel.setText(result.encodingDetail());
        setLockState(result);
        sha256Field.setText(result.sha256());
        sha1Field.setText(result.sha1());
        md5Field.setText(result.md5());
        setStatus("SUCCESS", "分析完成 · " + result.durationMillis() + " ms");
        updateButtons();
    }

    private void setLockState(FileAnalysis result) {
        lockStatusLabel.getStyleClass().removeAll(
                "status-offline", "status-busy", "status-online", "status-error");
        switch (result.lockState()) {
            case AVAILABLE -> {
                lockStatusLabel.setText("未发现占用");
                lockStatusLabel.getStyleClass().add("status-online");
            }
            case LOCKED -> {
                lockStatusLabel.setText("可能被占用");
                lockStatusLabel.getStyleClass().add("status-error");
            }
            case READ_ONLY -> {
                lockStatusLabel.setText("只读 / 无权限");
                lockStatusLabel.getStyleClass().add("status-busy");
            }
            case UNKNOWN -> {
                lockStatusLabel.setText("无法确认");
                lockStatusLabel.getStyleClass().add("status-offline");
            }
        }
        lockDetailLabel.setText(result.lockDetail());
    }

    private void failAnalysis(long analysisId, Exception exception) {
        if (cleaned || analysisGeneration.get() != analysisId) {
            return;
        }
        busy = false;
        currentAnalysis = null;
        setStatus("ERROR", errorMessage(exception));
        updateButtons();
    }

    private void copyHash(TextField field, String name) {
        if (busy) {
            return;
        }
        String value = field.getText();
        if (value == null || value.isBlank()) {
            setStatus("ERROR", "当前没有可复制的 " + name);
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("SUCCESS", name + " 已复制");
    }

    private void resetResult() {
        fileNameLabel.setText("--");
        fileSizeLabel.setText("--");
        fileTypeLabel.setText("--");
        encodingLabel.setText("--");
        modifiedLabel.setText("--");
        accessLabel.setText("--");
        encodingDetailLabel.setText("等待分析");
        lockStatusLabel.setText("未检测");
        lockStatusLabel.getStyleClass().removeAll(
                "status-busy", "status-online", "status-error");
        if (!lockStatusLabel.getStyleClass().contains("status-offline")) {
            lockStatusLabel.getStyleClass().add("status-offline");
        }
        lockDetailLabel.setText("等待读取文件占用线索");
        sha256Field.clear();
        sha1Field.clear();
        md5Field.clear();
        updateButtons();
    }

    private void updateButtons() {
        selectButton.setDisable(busy);
        analyzeButton.setDisable(busy || selectedFile == null);
        cancelButton.setDisable(!busy);
        copySha256Button.setDisable(busy || sha256Field.getText().isBlank());
        copySha1Button.setDisable(busy || sha1Field.getText().isBlank());
        copyMd5Button.setDisable(busy || md5Field.getText().isBlank());
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

    private static String formatBytes(long bytes) {
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
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.2f %s",
                value, units[unit]);
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
