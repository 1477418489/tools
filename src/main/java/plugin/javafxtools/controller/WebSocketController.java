package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.control.LogViewer;
import plugin.javafxtools.util.LogTextTrimmer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket客户端控制器 - 处理WebSocket连接和消息通信
 */
public class WebSocketController extends BaseController {
    private static final int MAX_MESSAGE_LINES = 1_000;
    private static final int MAX_PENDING_MESSAGES = 2_000;
    private static final int MAX_PENDING_CHARACTERS = 2_000_000;
    private static final int MAX_SINGLE_MESSAGE_CHARACTERS = 200_000;
    private static final int MAX_DISPLAY_CHARACTERS = 1_000_000;
    private static final int DISPLAY_TRIM_TARGET_CHARACTERS = 750_000;
    private static final int MESSAGE_BATCH_SIZE = 100;

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
    private TextArea wsMessageArea;

    @FXML
    private LogViewer wsMessageViewer;

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

    @FXML
    private Label wsStatusLabel;

    /**
     * 当前 WebSocket 客户端实例。
     */
    private volatile WebSocketClient webSocketClient;

    /**
     * 当前连接状态，由 JavaFX 线程和 WebSocket 回调线程共同访问。
     */
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private final Queue<String> pendingMessages = new ArrayDeque<>();
    private final AtomicBoolean messageFlushPending = new AtomicBoolean();
    private final Object pendingMessageLock = new Object();
    private int pendingMessageCount;
    private int pendingCharacterCount;
    private long droppedMessageCount;
    private volatile boolean disposed;


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
        wsMessageArea = wsMessageViewer.getTextArea();
        wsMessageViewer.setOnClear(this::handleWsClear);
        disposed = false;
        updateConnectionButtons(ConnectionState.DISCONNECTED);
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

        URI serverUri;
        try {
            serverUri = new URI(url);
            String scheme = serverUri.getScheme();
            if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))) {
                throw new URISyntaxException(url, "仅支持 ws 或 wss 协议");
            }
            if (serverUri.getHost() == null || serverUri.getHost().isBlank()) {
                throw new URISyntaxException(url, "地址缺少有效主机名");
            }
        } catch (URISyntaxException e) {
            error("无效的WebSocket URL: " + e.getMessage());
            return;
        }

        if (!beginConnectionAttempt()) {
            debug("WebSocket连接正在建立或已经建立");
            return;
        }
        updateConnectionButtons(ConnectionState.CONNECTING);

        WebSocketClient client = null;
        try {
            client = createWebSocketClient(serverUri);
            webSocketClient = client;
            client.connect();
            info("正在连接WebSocket服务器: " + url);
        } catch (RuntimeException e) {
            if (client == null) {
                resetUnassignedConnectionAttempt();
            } else {
                resetCurrentClient(client);
            }
            updateConnectionButtons(ConnectionState.DISCONNECTED);
            error("连接WebSocket失败: " + e.getMessage());
        }
    }

    private WebSocketClient createWebSocketClient(URI serverUri) {
        return new WebSocketClient(serverUri) {
                /**
                 * WebSocket 连接建立后的回调。
                 *
                 * @param handshakedata 服务端握手信息
                 */
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    if (!markConnected(this)) {
                        close();
                        return;
                    }
                    Platform.runLater(() -> updateButtonsIfCurrent(this, ConnectionState.CONNECTED));
                    info("WebSocket连接已建立");
                }

                /**
                 * 接收到服务端消息后的回调。
                 *
                 * @param message 服务端消息
                 */
                @Override
                public void onMessage(String message) {
                    if (isCurrentClient(this)) {
                        appendMessage("收到", message);
                    }
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
                    if (resetCurrentClient(this)) {
                        Platform.runLater(() -> updateConnectionButtons(ConnectionState.DISCONNECTED));
                        info("WebSocket连接已关闭: " + reason + " (code: " + code + ")");
                    }
                }

                /**
                 * WebSocket 异常回调。
                 *
                 * @param ex 异常信息
                 */
                @Override
                public void onError(Exception ex) {
                    if (!isCurrentClient(this)) {
                        return;
                    }
                    error("WebSocket错误: " + ex.getMessage());
                    if (!isOpen() && resetCurrentClient(this)) {
                        Platform.runLater(() -> updateConnectionButtons(ConnectionState.DISCONNECTED));
                    }
                }
            };
    }

    /**
     * 处理"断开"按钮点击事件
     */
    @FXML
    private void handleWsDisconnect() {
        DisconnectAction action = beginDisconnect();
        if (action == null) {
            return;
        }

        updateConnectionButtons(action.state());
        try {
            action.client().close();
        } catch (RuntimeException e) {
            if (resetCurrentClient(action.client())) {
                updateConnectionButtons(ConnectionState.DISCONNECTED);
            }
            error("断开WebSocket连接失败: " + e.getMessage());
        }
    }

    /**
     * 处理"发送"按钮点击事件
     */
    @FXML
    private void handleWsSend() {
        String message = wsMessageField.getText();
        if (message == null || message.isBlank()) {
            debug("消息不能为空");
            return;
        }

        WebSocketClient client = webSocketClient;
        if (connectionState == ConnectionState.CONNECTED && client != null && client.isOpen()) {
            try {
                client.send(message);
                appendMessage("发送", message);
                wsMessageField.clear();
            } catch (RuntimeException e) {
                error("发送WebSocket消息失败: " + e.getMessage());
                if (!client.isOpen() && resetCurrentClient(client)) {
                    updateConnectionButtons(ConnectionState.DISCONNECTED);
                }
            }
        } else {
            error("WebSocket连接未建立，无法发送消息");
        }
    }

    /**
     * 处理"清除"按钮点击事件
     */
    @FXML
    private void handleWsClear() {
        clearPendingMessages();
        handleClearLog();
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        disposed = true;
        clearPendingMessages();
        WebSocketClient client;
        synchronized (this) {
            client = webSocketClient;
            webSocketClient = null;
            connectionState = ConnectionState.DISCONNECTED;
        }
        if (client != null) {
            client.close();
        }
        super.cleanup();
    }

    private synchronized boolean beginConnectionAttempt() {
        if (connectionState != ConnectionState.DISCONNECTED || webSocketClient != null) {
            return false;
        }
        connectionState = ConnectionState.CONNECTING;
        return true;
    }

    private synchronized void resetUnassignedConnectionAttempt() {
        if (webSocketClient == null && connectionState == ConnectionState.CONNECTING) {
            connectionState = ConnectionState.DISCONNECTED;
        }
    }

    private synchronized boolean markConnected(WebSocketClient client) {
        if (webSocketClient != client || connectionState != ConnectionState.CONNECTING) {
            return false;
        }
        connectionState = ConnectionState.CONNECTED;
        return true;
    }

    private synchronized DisconnectAction beginDisconnect() {
        if (webSocketClient == null
                || connectionState == ConnectionState.DISCONNECTED
                || connectionState == ConnectionState.CLOSING) {
            return null;
        }

        WebSocketClient client = webSocketClient;
        if (connectionState == ConnectionState.CONNECTING) {
            webSocketClient = null;
            connectionState = ConnectionState.DISCONNECTED;
            return new DisconnectAction(client, ConnectionState.DISCONNECTED);
        }

        connectionState = ConnectionState.CLOSING;
        return new DisconnectAction(client, ConnectionState.CLOSING);
    }

    private synchronized boolean resetCurrentClient(WebSocketClient client) {
        if (webSocketClient != client) {
            return false;
        }
        webSocketClient = null;
        connectionState = ConnectionState.DISCONNECTED;
        return true;
    }

    private synchronized boolean isCurrentClient(WebSocketClient client) {
        return webSocketClient == client;
    }

    private void updateButtonsIfCurrent(WebSocketClient client, ConnectionState state) {
        if (isCurrentClient(client) && connectionState == state) {
            updateConnectionButtons(state);
        }
    }

    private void updateConnectionButtons(ConnectionState state) {
        wsConnectButton.setDisable(state != ConnectionState.DISCONNECTED);
        wsDisconnectButton.setDisable(
                state == ConnectionState.DISCONNECTED || state == ConnectionState.CLOSING);
        wsSendButton.setDisable(state != ConnectionState.CONNECTED);
        wsUrlField.setDisable(state != ConnectionState.DISCONNECTED);
        wsMessageField.setDisable(state != ConnectionState.CONNECTED);
        updateConnectionStatus(state);
    }

    private void updateConnectionStatus(ConnectionState state) {
        wsStatusLabel.getStyleClass().removeAll("status-offline", "status-busy", "status-online");
        switch (state) {
            case DISCONNECTED -> {
                wsStatusLabel.setText("未连接");
                wsStatusLabel.getStyleClass().add("status-offline");
            }
            case CONNECTING -> {
                wsStatusLabel.setText("连接中");
                wsStatusLabel.getStyleClass().add("status-busy");
            }
            case CONNECTED -> {
                wsStatusLabel.setText("已连接");
                wsStatusLabel.getStyleClass().add("status-online");
            }
            case CLOSING -> {
                wsStatusLabel.setText("断开中");
                wsStatusLabel.getStyleClass().add("status-busy");
            }
        }
    }

    private void appendMessage(String direction, String message) {
        if (disposed) {
            return;
        }
        String formattedMessage = direction + ": " + limitDisplayMessage(message) + "\n";
        synchronized (pendingMessageLock) {
            pendingMessages.offer(formattedMessage);
            pendingMessageCount++;
            pendingCharacterCount += formattedMessage.length();
            while ((pendingMessageCount > MAX_PENDING_MESSAGES
                    || pendingCharacterCount > MAX_PENDING_CHARACTERS)
                    && pendingMessageCount > 1) {
                String removed = pendingMessages.poll();
                if (removed == null) {
                    pendingMessageCount = 0;
                    pendingCharacterCount = 0;
                    break;
                }
                pendingMessageCount--;
                pendingCharacterCount -= removed.length();
                droppedMessageCount++;
            }
        }
        scheduleMessageFlush();
    }

    static String limitDisplayMessage(String message) {
        if (message == null || message.length() <= MAX_SINGLE_MESSAGE_CHARACTERS) {
            return message == null ? "" : message;
        }

        int endIndex = MAX_SINGLE_MESSAGE_CHARACTERS;
        if (Character.isHighSurrogate(message.charAt(endIndex - 1))
                && Character.isLowSurrogate(message.charAt(endIndex))) {
            endIndex--;
        }
        return message.substring(0, endIndex)
                + "\n[消息过长，已截断；原始字符数: " + message.length() + "]";
    }

    private void scheduleMessageFlush() {
        if (disposed || !messageFlushPending.compareAndSet(false, true)) {
            return;
        }
        try {
            Platform.runLater(this::flushPendingMessages);
        } catch (IllegalStateException e) {
            messageFlushPending.set(false);
        }
    }

    private void flushPendingMessages() {
        if (disposed) {
            clearPendingMessages();
            messageFlushPending.set(false);
            return;
        }

        List<String> batch = new ArrayList<>(MESSAGE_BATCH_SIZE);
        long dropped;
        boolean hasMore;
        synchronized (pendingMessageLock) {
            dropped = droppedMessageCount;
            droppedMessageCount = 0;
            while (batch.size() < MESSAGE_BATCH_SIZE) {
                String message = pendingMessages.poll();
                if (message == null) {
                    break;
                }
                batch.add(message);
                pendingMessageCount--;
                pendingCharacterCount -= message.length();
            }
            hasMore = pendingMessageCount > 0;
        }

        if (dropped > 0 || !batch.isEmpty()) {
            StringBuilder text = new StringBuilder();
            if (dropped > 0) {
                text.append("系统: 高负载期间已省略 ")
                        .append(dropped)
                        .append(" 条积压消息\n");
            }
            batch.forEach(text::append);
            LogTextTrimmer.trimToMaxLines(wsMessageArea, MAX_MESSAGE_LINES, 120);
            wsMessageArea.appendText(text.toString());
            LogTextTrimmer.trimToMaxCharacters(
                    wsMessageArea, MAX_DISPLAY_CHARACTERS, DISPLAY_TRIM_TARGET_CHARACTERS);
        }

        if (hasMore) {
            try {
                Platform.runLater(this::flushPendingMessages);
                return;
            } catch (IllegalStateException ignored) {
                clearPendingMessages();
            }
        }

        messageFlushPending.set(false);
        synchronized (pendingMessageLock) {
            hasMore = pendingMessageCount > 0;
        }
        if (hasMore) {
            scheduleMessageFlush();
        }
    }

    private void clearPendingMessages() {
        synchronized (pendingMessageLock) {
            pendingMessages.clear();
            pendingMessageCount = 0;
            pendingCharacterCount = 0;
            droppedMessageCount = 0;
        }
    }

    private enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CLOSING
    }

    private record DisconnectAction(WebSocketClient client, ConnectionState state) {
    }
}
