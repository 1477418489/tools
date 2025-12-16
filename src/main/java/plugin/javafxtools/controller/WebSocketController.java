package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.util.TimeUtils;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * WebSocket客户端控制器 - 处理WebSocket连接和消息通信
 */
public class WebSocketController implements ModuleLogger {

    @FXML
    private TextField wsUrlField;          // WebSocket服务器地址
    @FXML
    private Button wsConnectButton;        // 连接按钮
    @FXML
    private Button wsDisconnectButton;     // 断开按钮
    @FXML
    private TextArea wsMessageArea;        // 消息记录区
    @FXML
    private TextField wsMessageField;      // 消息输入框
    @FXML
    private Button wsSendButton;           // 发送按钮
    @FXML
    private Button wsClearButton;          // 清除按钮

    private WebSocketClient webSocketClient;     // WebSocket客户端实例


    /**
     * 自定义日志方法 - 只输出到本模块日志区
     */
    @Override
    public void log(String level, String message) {
        String formattedMessage = String.format("\n"+"[%s][%s] %s",
                TimeUtils.getCurrentDateTime(), level, message);
        Platform.runLater(() -> {
            if (wsMessageArea != null && wsMessageArea.getScene() != null) {
                wsMessageArea.appendText(formattedMessage);
                wsMessageArea.setScrollTop(Double.MAX_VALUE); // 自动滚动到底部
            }
        });
    }

    public TextArea getLogArea() {
        return wsMessageArea; // 或 return messageArea（根据实际变量名调整）
    }

    /**
     * 初始化方法 - 由JavaFX自动调用
     */
    @FXML
    public void initialize() {
        // 初始化按钮状态
        wsDisconnectButton.setDisable(true);
        wsSendButton.setDisable(true);
        // 设置默认WebSocket服务器地址
        wsUrlField.setText("ws://echo.websocket.org");
        wsMessageField.setPromptText("输入要发送的消息...");
        info("WebSocket客户端控制器模块初始化完成");
    }

    /**
     * 处理"连接"按钮点击事件
     */
    @FXML
    private void handleWsConnect() {
        String url = wsUrlField.getText().trim();
        if (url.isEmpty()) {
            error("请输入WebSocket服务器URL");
            return;
        }
        try {
            // 创建WebSocket客户端
            webSocketClient = new WebSocketClient(new URI(url)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Platform.runLater(() -> {
                        wsConnectButton.setDisable(true);
                        wsDisconnectButton.setDisable(false);
                        wsSendButton.setDisable(false);
                    });
                    info("WebSocket连接已建立");
                }

                @Override
                public void onMessage(String message) {
                    Platform.runLater(() -> {
                        wsMessageArea.appendText("收到: " + message + "\n");
                    });
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Platform.runLater(() -> {
                        wsConnectButton.setDisable(false);
                        wsDisconnectButton.setDisable(true);
                        wsSendButton.setDisable(true);
                    });
                    info("WebSocket连接已关闭: " + reason + " (code: " + code + ")");
                }

                @Override
                public void onError(Exception ex) {
                    error("WebSocket错误: " + ex.getMessage());
                }
            };

            // 发起连接
            webSocketClient.connect();
            info("正在连接WebSocket服务器: " + url);

        } catch (URISyntaxException e) {
            error("无效的WebSocket URL: " + e.getMessage());
        } catch (Exception e) {
            error("连接WebSocket失败: " + e.getMessage());
        }
    }

    /**
     * 处理"断开"按钮点击事件
     */
    @FXML
    private void handleWsDisconnect() {
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    /**
     * 处理"发送"按钮点击事件
     */
    @FXML
    private void handleWsSend() {
        String message = wsMessageField.getText().trim();
        if (message.isEmpty()) {
            debug("消息不能为空");
            return;
        }

        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.send(message);
            wsMessageArea.appendText("发送: " + message + "\n");
            wsMessageField.clear();
        } else {
            error("WebSocket连接未建立，无法发送消息");
        }
    }

    /**
     * 处理"清除"按钮点击事件
     */
    @FXML
    private void handleWsClear() {
        wsMessageArea.clear();
        info("已清除消息记录");
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        // 关闭WebSocket连接
        if (webSocketClient != null) {
            webSocketClient.close();
            webSocketClient = null;
        }
        System.out.println("WebSocketController 资源已清理");
    }
}