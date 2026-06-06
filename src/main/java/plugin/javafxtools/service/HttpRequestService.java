package plugin.javafxtools.service;

import plugin.javafxtools.model.HttpRequestResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HTTP 请求发送、请求头解析和响应体截断处理。
 *
 * @author wwj
 */
public class HttpRequestService {
    private static final int MAX_RESPONSE_BODY_CHARS = 200_000;

    private final HttpResponseFormatter responseFormatter;

    /**
     * 创建 HTTP 请求执行服务。
     *
     * @param responseFormatter 响应格式化服务
     */
    public HttpRequestService(HttpResponseFormatter responseFormatter) {
        this.responseFormatter = responseFormatter;
    }

    /**
     * 发送 HTTP 请求。
     *
     * @param urlStr 请求地址
     * @param method HTTP 方法
     * @param params 请求参数或请求体
     * @param headers 请求头文本
     * @param connectTimeout 连接超时
     * @param readTimeout 读取超时
     * @return 请求结果
     * @throws IOException 请求失败
     */
    public HttpRequestResult sendRequest(String urlStr,
                                         String method,
                                         String params,
                                         String headers,
                                         int connectTimeout,
                                         int readTimeout) throws IOException {
        List<String[]> customHeaders = parseHeaders(headers);
        String fullUrl = buildRequestUrl(urlStr, method, params);
        HttpURLConnection connection = null;

        try {
            URL url = new URL(fullUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "JavaFX-HTTP-Client");
            applyCustomHeaders(connection, customHeaders);
            writeRequestBodyIfNeeded(connection, method, params, customHeaders);

            int responseCode = connection.getResponseCode();
            String headerLog = buildHeaderLog(responseCode, connection.getHeaderFields());
            String responseBody = readResponseBody(connection, responseCode);
            return new HttpRequestResult(
                    headerLog + responseFormatter.limitForLog(responseBody),
                    responseBody
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildRequestUrl(String urlStr, String method, String params) {
        if (!(method.equalsIgnoreCase("GET") || method.equalsIgnoreCase("HEAD"))
                || params == null || params.isEmpty()) {
            return urlStr;
        }

        String encodedParams = Arrays.stream(params.split("&"))
                .map(String::trim)
                .map(this::encodeQueryPair)
                .collect(Collectors.joining("&"));
        return urlStr + (urlStr.contains("?") ? "&" : "?") + encodedParams;
    }

    private String encodeQueryPair(String pair) {
        int idx = pair.indexOf('=');
        if (idx <= 0) {
            return pair;
        }
        try {
            return URLEncoder.encode(pair.substring(0, idx), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(pair.substring(idx + 1), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return pair;
        }
    }

    private void applyCustomHeaders(HttpURLConnection connection, List<String[]> customHeaders) {
        for (String[] kv : customHeaders) {
            if (kv.length == 2) {
                connection.setRequestProperty(kv[0], kv[1]);
            }
        }
    }

    private void writeRequestBodyIfNeeded(HttpURLConnection connection,
                                          String method,
                                          String params,
                                          List<String[]> customHeaders) throws IOException {
        if (!Arrays.asList("POST", "PUT", "PATCH").contains(method.toUpperCase())) {
            return;
        }

        connection.setDoOutput(true);
        if (getHeaderValue(customHeaders, "Content-Type") == null) {
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = params.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
    }

    private String buildHeaderLog(int responseCode, Map<String, List<String>> responseHeaders) {
        StringBuilder headerLog = new StringBuilder("响应状态: " + responseCode + "\n");
        responseHeaders.forEach((key, value) -> {
            if (key != null) {
                headerLog.append(key).append(": ").append(String.join("; ", value)).append("\n");
            }
        });
        return headerLog.toString();
    }

    private String readResponseBody(HttpURLConnection connection, int responseCode) throws IOException {
        StringBuilder responseBody = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                responseCode < HttpURLConnection.HTTP_BAD_REQUEST
                        ? connection.getInputStream()
                        : connection.getErrorStream(), StandardCharsets.UTF_8))) {
            String responseLine;
            boolean truncated = false;
            while ((responseLine = reader.readLine()) != null) {
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
        return responseBody.toString().trim();
    }

    private List<String[]> parseHeaders(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && line.contains(":"))
                .map(line -> {
                    int idx = line.indexOf(':');
                    return new String[]{line.substring(0, idx).trim(), line.substring(idx + 1).trim()};
                }).collect(Collectors.toList());
    }

    private String getHeaderValue(List<String[]> headers, String key) {
        for (String[] kv : headers) {
            if (kv[0].equalsIgnoreCase(key)) {
                return kv[1];
            }
        }
        return null;
    }
}
