package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import plugin.javafxtools.base.BaseController;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * WebSocket客户端控制器 - 处理WebSocket连接和消息通信
 */
public class WebSocketController extends BaseController {

    /**
     * WebSocket 服务器地址输入框。
     */
    @FXML
    private TextField wsUrlField;

    /**
     * 建立 WebSocket 连接的按钮。
     */
    @FXML
    private Button wsConnectButton;

    /**
     * 断开 WebSocket 连接的按钮。
     */
    @FXML
    private Button wsDisconnectButton;

    /**
     * 消息记录和模块日志输出区。
     */
    @FXML
    private TextArea wsMessageArea;

    /**
     * 待发送消息输入框。
     */
    @FXML
    private TextField wsMessageField;

    /**
     * 发送消息按钮。
     */
    @FXML
    private Button wsSendButton;

    /**
     * 清空消息记录按钮。
     */
    @FXML
    private Button wsClearButton;

    /**
     * 当前 WebSocket 客户端实例。
     */
    private WebSocketClient webSocketClient;


    /**
     * 获取当前模块日志输出区域。
     *
     * @return WebSocket 消息记录区域
     */
    @Override
    public TextArea getLogArea() {
        return wsMessageArea;
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
                /**
                 * WebSocket 连接建立后的回调。
                 *
                 * @param handshakedata 服务端握手信息
                 */
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Platform.runLater(() -> {
                        wsConnectButton.setDisable(true);
                        wsDisconnectButton.setDisable(false);
                        wsSendButton.setDisable(false);
                    });
                    info("WebSocket连接已建立");
                }

                /**
                 * 接收到服务端消息后的回调。
                 *
                 * @param message 服务端消息
                 */
                @Override
                public void onMessage(String message) {
                    Platform.runLater(() -> {
                        wsMessageArea.appendText("收到: " + message + "\n");
                    });
                }

                /**
                 * WebSocket 连接关闭后的回调。
                 *
                 * @param code 关闭状态码
                 * @param reason 关闭原因
                 * @param remote 是否由远端关闭
                 */
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Platform.runLater(() -> {
                        wsConnectButton.setDisable(false);
                        wsDisconnectButton.setDisable(true);
                        wsSendButton.setDisable(true);
                    });
                    info("WebSocket连接已关闭: " + reason + " (code: " + code + ")");
                }

                /**
                 * WebSocket 异常回调。
                 *
                 * @param ex 异常信息
                 */
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
    }
}
