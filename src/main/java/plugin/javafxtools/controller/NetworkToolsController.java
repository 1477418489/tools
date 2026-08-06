package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import plugin.javafxtools.base.BaseController;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网络查询工具控制器 - 提供IP/DNS查询功能
 */
public class NetworkToolsController extends BaseController {

    /**
     * 主机名或 IP 输入框。
     */
    @FXML
    private TextField hostField;

    /**
     * 执行网络查询的按钮。
     */
    @FXML
    private Button lookupButton;

    /**
     * 清空查询条件和结果的按钮。
     */
    @FXML
    private Button clearButton;

    @FXML
    private Button copyButton;

    @FXML
    private Label lookupStatusLabel;

    /**
     * 网络查询结果和模块日志输出区域。
     */
    @FXML
    private TextArea lookupResultArea;

    /**
     * 后台网络查询执行器，避免 DNS 和可达性检测阻塞 JavaFX 线程。
     */
    private final ExecutorService queryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NetworkQuery");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong queryGeneration = new AtomicLong();

    private volatile Future<?> currentQuery;

    private boolean queryRunning;

    /**
     * 获取当前模块日志输出区域。
     *
     * @return 网络查询结果区域
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
        lookupResultArea.setPromptText("查询结果将在这里显示");
        hostField.textProperty().addListener((observable, oldValue, newValue) -> updateButtonStates());
        lookupResultArea.textProperty().addListener((observable, oldValue, newValue) -> updateButtonStates());
        setStatus("READY", "就绪");
        updateButtonStates();
    }

    /**
     * 处理查询按钮点击事件
     */
    @FXML
    private void handleLookup() {
        if (queryRunning) {
            return;
        }
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            error("请输入要查询的主机名或IP地址");
            return;
        }

        long queryId = queryGeneration.incrementAndGet();
        queryRunning = true;
        setStatus("BUSY", "查询中");
        updateButtonStates();

        try {
            currentQuery = queryExecutor.submit(() -> {
                try {
                    InetAddress[] addresses = InetAddress.getAllByName(host);
                    StringBuilder result = new StringBuilder();

                    result.append("查询目标: ").append(host).append("\n\n");

                    for (InetAddress addr : addresses) {
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        result.append("主机名: ").append(addr.getHostName()).append("\n");
                        result.append("IP地址: ").append(addr.getHostAddress()).append("\n");
                        result.append("规范主机名: ").append(addr.getCanonicalHostName()).append("\n");

                        boolean reachable = addr.isReachable(3000);
                        result.append("是否可达: ").append(reachable ? "是" : "否").append("\n");

                        result.append("回环地址: ").append(addr.isLoopbackAddress() ? "是" : "否").append("\n");
                        result.append("本地地址: ").append(addr.isSiteLocalAddress() ? "是" : "否").append("\n");
                        result.append("多播地址: ").append(addr.isMulticastAddress() ? "是" : "否").append("\n");
                        result.append("\n");
                    }

                    finishQuery(queryId, result.toString().stripTrailing(), "SUCCESS", "查询完成");
                } catch (UnknownHostException e) {
                    finishQuery(queryId, "无法解析主机: " + host + "\n错误信息: " + e.getMessage(),
                            "ERROR", "DNS 查询失败");
                } catch (IOException e) {
                    finishQuery(queryId, "网络错误: " + e.getMessage(), "ERROR", "网络查询失败");
                } catch (Exception e) {
                    finishQuery(queryId,
                            "查询失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                            "ERROR", "网络查询失败");
                }
            });
        } catch (RejectedExecutionException e) {
            queryRunning = false;
            setStatus("ERROR", "查询服务已关闭");
            updateButtonStates();
        }
    }

    /**
     * 处理清除按钮点击事件
     */
    @FXML
    private void handleClear() {
        queryGeneration.incrementAndGet();
        Future<?> query = currentQuery;
        if (query != null) {
            query.cancel(true);
            currentQuery = null;
        }
        queryRunning = false;
        hostField.clear();
        lookupResultArea.clear();
        setStatus("READY", "就绪");
        updateButtonStates();
        hostField.requestFocus();
    }

    /**
     * 复制当前查询结果。
     */
    @FXML
    private void handleCopyResult() {
        String result = lookupResultArea.getText();
        if (result == null || result.isBlank()) {
            setStatus("ERROR", "当前没有可复制的结果");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(result);
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("SUCCESS", "结果已复制");
    }

    /**
     * 清理网络查询线程池。
     */
    @Override
    public void cleanup() {
        queryGeneration.incrementAndGet();
        Future<?> query = currentQuery;
        if (query != null) {
            query.cancel(true);
        }
        queryExecutor.shutdownNow();
    }

    /**
     * 页面状态与查询结果分离，避免状态日志污染可复制结果。
     */
    @Override
    public void log(String level, String message) {
        setStatus(level, message);
    }

    private void finishQuery(long queryId, String result, String state, String statusText) {
        Platform.runLater(() -> {
            if (queryGeneration.get() != queryId) {
                return;
            }
            currentQuery = null;
            queryRunning = false;
            lookupResultArea.setText(result);
            setStatus(state, statusText);
            updateButtonStates();
        });
    }

    private void updateButtonStates() {
        boolean hasHost = hostField.getText() != null && !hostField.getText().isBlank();
        boolean hasResult = lookupResultArea.getText() != null && !lookupResultArea.getText().isBlank();
        lookupButton.setDisable(queryRunning || !hasHost);
        copyButton.setDisable(!hasResult);
        clearButton.setDisable(!queryRunning && !hasHost && !hasResult);
    }

    private void setStatus(String state, String message) {
        Runnable update = () -> {
            lookupStatusLabel.setText(message);
            lookupStatusLabel.getStyleClass().removeAll(
                    "status-offline", "status-busy", "status-online", "feedback-error");
            if ("BUSY".equals(state)) {
                lookupStatusLabel.getStyleClass().add("status-busy");
            } else if ("SUCCESS".equals(state) || "INFO".equals(state)) {
                lookupStatusLabel.getStyleClass().add("status-online");
            } else if ("ERROR".equals(state)) {
                lookupStatusLabel.getStyleClass().addAll("status-offline", "feedback-error");
            } else {
                lookupStatusLabel.getStyleClass().add("status-offline");
            }
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }
}
