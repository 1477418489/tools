package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.model.HttpTemplate;
import plugin.javafxtools.util.TimeUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 主要功能：
 * - 支持GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS方法
 * - 支持自定义请求头
 * - 支持请求模板的保存与加载
 * - 支持设置连接/读取超时时间
 * - 支持响应内容格式美化（只对最新响应体美化，不影响日志和header）
 * - 响应区显示状态码及header信息
 * - 代码结构优化，关键步骤均有注释
 */
public class HttpRequestController extends BaseController {

    /**
     * 定时请求开始时间输入框。
     */
    @FXML private TextField startTimeField;

    /**
     * 定时请求间隔秒数输入框。
     */
    @FXML private TextField intervalField;

    /**
     * HTTP 请求地址输入框。
     */
    @FXML private TextField urlField;

    /**
     * HTTP 请求方法选择框。
     */
    @FXML private ComboBox<String> methodComboBox;

    /**
     * 请求参数输入区。
     */
    @FXML private TextArea paramsArea;

    /**
     * 请求头输入区。
     */
    @FXML private TextArea headersArea;

    /**
     * 请求日志和响应输出区。
     */
    @FXML private TextArea logArea;

    /**
     * 启动定时请求按钮。
     */
    @FXML private Button startButton;

    /**
     * 停止定时请求按钮。
     */
    @FXML private Button stopButton;

    /**
     * 将开始时间设置为当前时间的按钮。
     */
    @FXML private Button nowButton;

    /**
     * 格式化最近响应体按钮。
     */
    @FXML private Button formatButton;

    /**
     * 响应体格式化策略选择框。
     */
    @FXML private ComboBox<String> responseFormatComboBox;

    /**
     * 请求模板选择框。
     */
    @FXML private ComboBox<String> templateComboBox;

    /**
     * 连接超时时间输入框。
     */
    @FXML private TextField connectTimeoutField;

    /**
     * 读取超时时间输入框。
     */
    @FXML private TextField readTimeoutField;

    /**
     * 定时请求调度器。
     */
    private ScheduledExecutorService scheduler;

    /**
     * 当前正在执行或排队的请求任务。
     */
    private Future<?> currentTaskFuture;

    /**
     * 定时请求运行状态。
     */
    private boolean isRunning = false;

    /**
     * 最近一次 HTTP 响应体，用于格式化按钮处理。
     */
    private String lastRawResponseBody = null;

    /**
     * 已加载的请求模板集合。
     */
    private final Map<String, HttpTemplate> templates = new HashMap<>();

    /**
     * 请求模板持久化文件路径。
     */
    private static final String TEMPLATE_FILE = "http_templates.json";

    /**
     * 单次响应体最多保留字符数，避免大响应撑爆内存和日志区域。
     */
    private static final int MAX_RESPONSE_BODY_CHARS = 200_000;

    /**
     * 单次日志中响应体最多展示字符数，避免 TextArea 一次写入过大导致卡顿。
     */
    private static final int MAX_RESPONSE_LOG_CHARS = 20_000;

    /**
     * 获取当前模块日志输出区域。
     *
     * @return HTTP 请求日志区域
     */
    @Override
    public TextArea getLogArea() { return logArea; }

    // ----------- 初始化 -----------
    @FXML
    public void initialize() {
        try {
            stopButton.setDisable(true);
            // 支持多种HTTP方法
            methodComboBox.getItems().addAll("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
            methodComboBox.setValue("GET");

            // 响应格式选择（自动检测/仅美化JSON/原始）
            responseFormatComboBox.getItems().addAll("Auto", "Pretty JSON", "Raw");
            responseFormatComboBox.setValue("Auto");

            // 默认时间、URL、间隔
            startTimeField.setText(TimeUtils.getCurrentDateTime());
            urlField.setText("https://jsonplaceholder.typicode.com/posts");
            intervalField.setText("10");
            connectTimeoutField.setText("5000");
            readTimeoutField.setText("10000");

            paramsArea.setPromptText("GET参数示例: userId=1&id=2\nPOST参数示例: {\"title\":\"foo\",\"body\":\"bar\",\"userId\":1}");
            headersArea.setPromptText("自定义Header，每行一个，例如：\nContent-Type: application/json\nAuthorization: Bearer ...");

            // 加载本地请求模板
            loadTemplates();
            updateTemplateComboBox();

            info("HTTP请求模块初始化完成");
        } catch (Exception e) {
            error("HTTP控制器初始化失败: " + e.getMessage());
        }
    }

    // ----------- UI事件处理 -----------

    /**
     * 现在按钮：快速设置当前时间为开始时间
     */
    @FXML
    private void handleNowButton() {
        startTimeField.setText(TimeUtils.getCurrentDateTime());
        info("已设置开始时间为当前时间");
    }

    /**
     * 响应美化按钮：仅对最近响应体JSON进行格式化，不影响日志和header
     */
    @FXML
    private void handleFormatButton() {
        if (lastRawResponseBody == null || lastRawResponseBody.isEmpty()) {
            info("无内容可格式化");
            return;
        }
        String formatted = tryPrettyJson(lastRawResponseBody);
        if (formatted != null) {
            log("INFO", "[美化后内容]\n" + formatted);
        } else {
            info("不是合法的JSON，无法美化");
        }
    }

    /**
     * 保存模板按钮
     */
    @FXML
    private void handleSaveTemplate() {
        String templateName = templateComboBox.getEditor().getText().trim();
        if (templateName.isEmpty()) {
            error("请输入模板名称");
            return;
        }
        HttpTemplate tpl = buildTemplateFromUI();
        templates.put(templateName, tpl);
        saveTemplates();
        updateTemplateComboBox();
        info("已保存模板: " + templateName);
    }

    /**
     * 加载模板按钮
     */
    @FXML
    private void handleLoadTemplate() {
        String tplName = templateComboBox.getValue();
        if (tplName == null || !templates.containsKey(tplName)) {
            error("请选择要加载的模板");
            return;
        }
        HttpTemplate tpl = templates.get(tplName);
        applyTemplateToUI(tpl);
        info("已载入模板: " + tplName);
    }

    /**
     * 删除模板按钮：从本地和下拉框中移除所选模板
     */
    @FXML
    private void handleDeleteTemplate() {
        String tplName = templateComboBox.getValue();
        if (tplName == null || !templates.containsKey(tplName)) {
            error("请选择要删除的模板");
            return;
        }
        templates.remove(tplName);
        saveTemplates();
        updateTemplateComboBox();
        info("已删除模板: " + tplName);
    }

    /**
     * 开始调度按钮
     */
    @FXML
    private void handleStartButton() {
        if (isRunning) {
            info("调度器已在运行中");
            return;
        }

        // 获取用户输入
        String startTimeStr = startTimeField.getText().trim();
        String intervalStr = intervalField.getText().trim();
        String urlStr = urlField.getText().trim();
        String method = methodComboBox.getValue();
        String params = paramsArea.getText().trim();

        // 验证输入
        if (startTimeStr.isEmpty() || intervalStr.isEmpty() || urlStr.isEmpty()) {
            error("请填写所有必填字段（开始时间、间隔、URL）");
            return;
        }

        try {
            Date startTime = TimeUtils.parseDateTime(startTimeStr, TimeUtils.DEFAULT_DATETIME_FORMAT);
            if (startTime == null) {
                error("开始时间格式不正确，请使用 yyyy-MM-dd HH:mm:ss 格式");
                return;
            }
            long interval = Long.parseLong(intervalStr) * 1000L;
            if (interval <= 0) {
                error("间隔必须大于0秒");
                return;
            }
            long delay = startTime.getTime() - System.currentTimeMillis();
            if (delay < 0) {
                debug("开始时间已过，将立即执行第一次请求");
                delay = 0;
            }

            int connectTimeout = parseIntOrDefault(connectTimeoutField.getText(), 5000);
            int readTimeout = parseIntOrDefault(readTimeoutField.getText(), 10000);

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HttpRequest-Scheduler");
                t.setDaemon(true);
                return t;
            });
            isRunning = true;
            startButton.setDisable(true);
            stopButton.setDisable(false);
            nowButton.setDisable(true);

            List<String[]> customHeaders = parseHeaders(headersArea.getText());

            // 执行HTTP请求的定时任务
            Runnable task = () -> {
                try {
                    info("准备发送 " + method + " 请求到: " + urlStr);
                    if (Arrays.asList("POST", "PUT", "PATCH").contains(method)) {
                        info("请求体: " + params);
                    }
                    // 实际发送HTTP请求
                    String logContent = sendRequest(urlStr, method, params, customHeaders, connectTimeout, readTimeout);
                    String respFormat = responseFormatComboBox.getValue();
                    String displayResp = lastRawResponseBody;
                    // 根据用户选择是否美化JSON
                    if ("Pretty JSON".equalsIgnoreCase(respFormat) || ("Auto".equalsIgnoreCase(respFormat) && isJson(lastRawResponseBody))) {
                        String pretty = tryPrettyJson(lastRawResponseBody);
                        if (pretty != null) displayResp = pretty;
                    }
                    displayResp = limitForLog(displayResp);
                    info("请求完成：\n" + logContent + (displayResp != null ? ("\n[响应体美化预览]\n" + displayResp) : ""));
                } catch (IOException e) {
                    error("请求失败: " + e.getMessage());
                } catch (Exception e) {
                    error("意外错误: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            };

            currentTaskFuture = scheduler.scheduleWithFixedDelay(task, delay, interval, TimeUnit.MILLISECONDS);
            info(String.format("调度器已启动，将在 %s 开始执行，间隔 %d 秒",
                    TimeUtils.formatDateTime(startTime, TimeUtils.DEFAULT_DATETIME_FORMAT), interval / 1000));
        } catch (Exception e) {
            error("启动调度器失败: " + e.getMessage());
            stopScheduler();
        }
    }

    /**
     * 停止调度按钮
     */
    @FXML
    private void handleStopButton() {
        stopScheduler();
        info("调度器已停止");
    }

    // ----------- 辅助功能 -----------

    /**
     * 停止调度并更新UI
     */
    private void stopScheduler() {
        isRunning = false;
        if (currentTaskFuture != null) currentTaskFuture.cancel(true);
        if (scheduler != null) scheduler.shutdownNow();
        Platform.runLater(() -> {
            startButton.setDisable(false);
            stopButton.setDisable(true);
            nowButton.setDisable(false);
        });
    }

    /**
     * 发送HTTP请求，支持自定义Header和超时
     * 并将响应体内容存入lastRawResponseBody
     */
    private String sendRequest(String urlStr, String method, String params,
                               List<String[]> customHeaders, int connectTimeout, int readTimeout) throws IOException {
        String fullUrl = urlStr;
        HttpURLConnection connection = null;
        try {
            // 处理GET/HEAD参数拼接
            if ((method.equalsIgnoreCase("GET") || method.equalsIgnoreCase("HEAD")) && !params.isEmpty()) {
                String encodedParams = Arrays.stream(params.split("&"))
                        .map(String::trim)
                        .map(p -> {
                            int idx = p.indexOf('=');
                            if (idx > 0) {
                                try {
                                    return URLEncoder.encode(p.substring(0, idx), "UTF-8") + "=" +
                                            URLEncoder.encode(p.substring(idx + 1), "UTF-8");
                                } catch (Exception e) {
                                    return p;
                                }
                            } else {
                                return p;
                            }
                        }).collect(Collectors.joining("&"));
                fullUrl += (urlStr.contains("?") ? "&" : "?") + encodedParams;
            }

            URL url = new URL(fullUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);

            // 通用请求头
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "JavaFX-HTTP-Client");

            // 自定义Header
            if (customHeaders != null) {
                for (String[] kv : customHeaders) {
                    if (kv.length == 2) {
                        connection.setRequestProperty(kv[0], kv[1]);
                    }
                }
            }

            // POST/PUT/PATCH写入请求体
            if (Arrays.asList("POST", "PUT", "PATCH").contains(method.toUpperCase())) {
                connection.setDoOutput(true);
                String contentType = getHeaderValue(customHeaders, "Content-Type");
                if (contentType == null) {
                    contentType = "application/json; charset=utf-8";
                    connection.setRequestProperty("Content-Type", contentType);
                }
                // 写入请求体
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = params.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
            }

            int responseCode = connection.getResponseCode();
            Map<String, List<String>> respHeaders = connection.getHeaderFields();
            StringBuilder headerStr = new StringBuilder("响应状态: " + responseCode + "\n");
            respHeaders.forEach((k, v) -> {
                if (k != null) headerStr.append(k).append(": ").append(String.join("; ", v)).append("\n");
            });

            // 读取响应体
            StringBuilder responseBody = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    responseCode < HttpURLConnection.HTTP_BAD_REQUEST ?
                            connection.getInputStream() : connection.getErrorStream(), "utf-8"))) {

                String responseLine;
                boolean truncated = false;
                while ((responseLine = br.readLine()) != null) {
                    if (responseBody.length() + responseLine.length() + 1 > MAX_RESPONSE_BODY_CHARS) {
                        int remaining = Math.max(0, MAX_RESPONSE_BODY_CHARS - responseBody.length());
                        if (remaining > 0) {
                            responseBody.append(responseLine, 0, Math.min(remaining, responseLine.length()));
                        }
                        truncated = true;
                        break;
                    }
                    responseBody.append(responseLine).append("\n");
                }
                if (truncated) {
                    responseBody.append("\n[响应体过大，已截断]");
                }
            }
            // 仅保存纯响应体，不包含header，用于格式化
            lastRawResponseBody = responseBody.toString().trim();

            // 将header和响应体返回用于日志区显示
            return headerStr.append(limitForLog(lastRawResponseBody)).toString();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 限制写入日志的响应体大小。
     *
     * @param text 原始响应体
     * @return 限制后的响应体
     */
    private String limitForLog(String text) {
        if (text == null || text.length() <= MAX_RESPONSE_LOG_CHARS) {
            return text;
        }
        return text.substring(0, MAX_RESPONSE_LOG_CHARS) + "\n[日志展示内容过大，已截断]";
    }

    /**
     * 解析header文本为键值对集合
     */
    private List<String[]> parseHeaders(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && line.contains(":"))
                .map(line -> {
                    int idx = line.indexOf(':');
                    return new String[]{line.substring(0, idx).trim(), line.substring(idx + 1).trim()};
                }).collect(Collectors.toList());
    }

    /**
     * 获取指定header值（忽略大小写）
     */
    private String getHeaderValue(List<String[]> headers, String key) {
        if (headers == null) return null;
        for (String[] kv : headers) {
            if (kv[0].equalsIgnoreCase(key)) return kv[1];
        }
        return null;
    }

    /**
     * 字符串转int，异常时返回默认值
     */
    private int parseIntOrDefault(String value, int def) {
        try { return Integer.parseInt(value); } catch (Exception e) { return def; }
    }

    /**
     * 判断字符串是否为JSON格式
     */
    private boolean isJson(String str) {
        if (str == null) return false;
        String s = str.trim();
        return (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"));
    }

    /**
     * 尝试美化JSON字符串
     */
    private String tryPrettyJson(String json) {
        try {
            Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
            Object obj = prettyGson.fromJson(json, Object.class);
            return prettyGson.toJson(obj);
        } catch (Exception ignore) {}
        return null;
    }

    // ----------- 模板存储与管理 -----------

    /**
     * 保存模板到本地文件
     */
    private void saveTemplates() {
        try (Writer writer = new FileWriter(TEMPLATE_FILE)) {
            new Gson().toJson(templates, writer);
        } catch (Exception e) {
            error("保存模板失败: " + e.getMessage());
        }
    }

    /**
     * 从本地加载模板
     */
    private void loadTemplates() {
        templates.clear();
        File file = new File(TEMPLATE_FILE);
        if (!file.exists()) return;
        try (Reader reader = new FileReader(file)) {
            Map<String, HttpTemplate> map = new Gson().fromJson(reader,
                    new TypeToken<Map<String, HttpTemplate>>(){}.getType());
            if (map != null) templates.putAll(map);
        } catch (Exception e) {
            error("加载模板失败: " + e.getMessage());
        }
    }

    /**
     * 刷新模板下拉列表
     */
    private void updateTemplateComboBox() {
        Platform.runLater(() -> {
            templateComboBox.getItems().setAll(templates.keySet());
        });
    }

    /**
     * 从UI读取，生成模板对象
     */
    private HttpTemplate buildTemplateFromUI() {
        return new HttpTemplate(
                urlField.getText(),
                methodComboBox.getValue(),
                paramsArea.getText(),
                headersArea.getText(),
                intervalField.getText(),
                connectTimeoutField.getText(),
                readTimeoutField.getText()
        );
    }

    /**
     * 应用模板到UI
     */
    private void applyTemplateToUI(HttpTemplate tpl) {
        urlField.setText(tpl.url);
        methodComboBox.setValue(tpl.method);
        paramsArea.setText(tpl.params);
        headersArea.setText(tpl.headers);
        intervalField.setText(tpl.interval);
        connectTimeoutField.setText(tpl.connectTimeout);
        readTimeoutField.setText(tpl.readTimeout);
    }

    // ----------- 清理 -----------

    /**
     * 资源清理
     */
    public void cleanup() {
        stopScheduler();
        info("HTTP请求模块资源已清理");
    }

}
