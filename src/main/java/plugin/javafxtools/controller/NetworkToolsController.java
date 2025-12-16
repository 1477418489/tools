package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.util.TimeUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;

/**
 * 网络查询工具控制器 - 提供IP/DNS查询功能
 */
public class NetworkToolsController implements ModuleLogger {

    @FXML
    private TextField hostField;         // 主机名/IP输入框
    @FXML
    private Button lookupButton;        // 查询按钮
    @FXML
    private Button clearButton;         // 清除按钮
    @FXML
    private TextArea lookupResultArea;  // 结果显示区域


    public TextArea getLogArea() {
        return lookupResultArea;
    }

    /**
     * 自定义日志方法 - 只输出到本模块日志区
     */
    @Override
    public void log(String level, String message) {
        String formattedMessage = String.format("\n"+"[%s][%s] %s",
                TimeUtils.getCurrentDateTime(), level, message);

        Platform.runLater(() -> {
            if (lookupResultArea != null && lookupResultArea.getScene() != null) {
                lookupResultArea.appendText(formattedMessage);
                lookupResultArea.setScrollTop(Double.MAX_VALUE); // 自动滚动到底部
            }
        });
    }

    /**
     * 初始化方法 - 由JavaFX自动调用
     */
    @FXML
    public void initialize() {
        // 设置默认提示文本
        hostField.setPromptText("输入域名(如baidu.com)或IP(如8.8.8.8)");
        lookupResultArea.setPromptText("查询结果将显示在这里...");
        info("网络查询工具控制器模块初始化完成");
    }

    /**
     * 处理查询按钮点击事件
     */
    @FXML
    private void handleLookup() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            error("请输入要查询的主机名或IP地址");
            return;
        }

        // 禁用按钮防止重复查询
        lookupButton.setDisable(true);
        clearButton.setDisable(true);

        // 使用线程池执行网络查询，避免阻塞UI线程
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                info("开始查询: " + host);

                // 获取所有关联的InetAddress
                InetAddress[] addresses = InetAddress.getAllByName(host);
                StringBuilder result = new StringBuilder();

                // 构建结果字符串
                result.append("=== 网络查询结果 ===\n");
                result.append("查询目标: ").append(host).append("\n\n");

                for (InetAddress addr : addresses) {
                    result.append("主机名: ").append(addr.getHostName()).append("\n");
                    result.append("IP地址: ").append(addr.getHostAddress()).append("\n");
                    result.append("规范主机名: ").append(addr.getCanonicalHostName()).append("\n");

                    // 测试可达性(3秒超时)
                    boolean reachable = addr.isReachable(3000);
                    result.append("是否可达: ").append(reachable ? "是" : "否").append("\n");

                    // 其他网络信息
                    result.append("回环地址: ").append(addr.isLoopbackAddress() ? "是" : "否").append("\n");
                    result.append("本地地址: ").append(addr.isSiteLocalAddress() ? "是" : "否").append("\n");
                    result.append("多播地址: ").append(addr.isMulticastAddress() ? "是" : "否").append("\n");
                    result.append("--------------------------------\n");
                }

                // 在UI线程更新结果
                Platform.runLater(() -> {
                    lookupResultArea.setText(result.toString());
                    lookupButton.setDisable(false);
                    clearButton.setDisable(false);
                });

                info("查询完成: " + host);

            } catch (UnknownHostException e) {
                Platform.runLater(() -> {
                    lookupResultArea.setText("无法解析主机: " + host + "\n错误信息: " + e.getMessage());
                    lookupButton.setDisable(false);
                    clearButton.setDisable(false);
                });
                error("DNS查询失败: " + e.getMessage());
            } catch (IOException e) {
                Platform.runLater(() -> {
                    lookupResultArea.setText("网络错误: " + e.getMessage());
                    lookupButton.setDisable(false);
                    clearButton.setDisable(false);
                });
                error("网络错误: " + e.getMessage());
            } catch (Exception e) {
                Platform.runLater(() -> {
                    lookupResultArea.setText("意外错误: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    lookupButton.setDisable(false);
                    clearButton.setDisable(false);
                });
                error("查询过程中发生意外错误: " + e.getMessage());
            }
        });
    }

    /**
     * 处理清除按钮点击事件
     */
    @FXML
    private void handleClear() {
        hostField.clear();
        lookupResultArea.clear();
        info("已清除查询条件和结果");
    }
    /**
     * 清空日志按钮
     */
    @FXML
    private void handleClearLog() {
        Platform.runLater(() -> {
            if (lookupResultArea != null) {
                lookupResultArea.clear();
            }
        });
    }
    /**
     * 清理资源
     */
    public void cleanup() {
    }
}