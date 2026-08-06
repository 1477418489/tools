package plugin.javafxtools.service;

import plugin.javafxtools.model.HttpRequestResult;
import plugin.javafxtools.util.HttpUrlSupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * HTTP 请求发送、请求头解析和响应体截断处理。
 *
 * @author wwj
 */
public class HttpRequestService {
    private static final int MAX_RESPONSE_BODY_CHARS = 200_000;
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile(
            "[!#$%&'*+.^_`|~0-9A-Za-z-]+");

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
            URL url = parseUrl(fullUrl);
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
            return new HttpRequestResult(headerLog, responseBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    String buildRequestUrl(String urlStr, String method, String params) {
        if (!(method.equalsIgnoreCase("GET") || method.equalsIgnoreCase("HEAD"))
                || params == null || params.isBlank()) {
            return urlStr;
        }

        String encodedParams = Arrays.stream(params.split("&"))
                .map(String::trim)
                .map(this::encodeQueryPair)
                .collect(Collectors.joining("&"));
        int fragmentIndex = urlStr.indexOf('#');
        String requestTarget = fragmentIndex >= 0 ? urlStr.substring(0, fragmentIndex) : urlStr;
        String fragment = fragmentIndex >= 0 ? urlStr.substring(fragmentIndex) : "";
        String separator;
        if (!requestTarget.contains("?")) {
            separator = "?";
        } else if (requestTarget.endsWith("?") || requestTarget.endsWith("&")) {
            separator = "";
        } else {
            separator = "&";
        }
        return requestTarget + separator + encodedParams + fragment;
    }

    private URL parseUrl(String value) throws IOException {
        return HttpUrlSupport.toUrl(value);
    }

    private String encodeQueryPair(String pair) {
        int idx = pair.indexOf('=');
        if (idx < 0) {
            return URLEncoder.encode(pair, StandardCharsets.UTF_8);
        }
        return URLEncoder.encode(pair.substring(0, idx), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(pair.substring(idx + 1), StandardCharsets.UTF_8);
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
        if (!Arrays.asList("POST", "PUT", "PATCH").contains(method.toUpperCase(Locale.ROOT))) {
            return;
        }

        connection.setDoOutput(true);
        if (getHeaderValue(customHeaders, "Content-Type") == null) {
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = (params == null ? "" : params).getBytes(StandardCharsets.UTF_8);
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

    String readResponseBody(HttpURLConnection connection, int responseCode) throws IOException {
        StringBuilder responseBody = new StringBuilder();
        InputStream responseStream = responseCode < HttpURLConnection.HTTP_BAD_REQUEST
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (responseStream == null) {
            return "";
        }

        try (Reader reader = new InputStreamReader(responseStream, responseCharset(connection))) {
            char[] buffer = new char[8_192];
            boolean truncated = false;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = MAX_RESPONSE_BODY_CHARS - responseBody.length();
                if (read > remaining) {
                    responseBody.append(buffer, 0, Math.max(remaining, 0));
                    truncated = true;
                    break;
                }
                responseBody.append(buffer, 0, read);
            }
            if (truncated) {
                if (!responseBody.isEmpty()
                        && responseBody.charAt(responseBody.length() - 1) != '\n') {
                    responseBody.append('\n');
                }
                responseBody.append("[响应体过大，已截断]");
            }
        }
        return responseBody.toString();
    }

    private Charset responseCharset(HttpURLConnection connection) {
        String contentType = connection.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }

        for (String parameter : contentType.split(";")) {
            int separatorIndex = parameter.indexOf('=');
            if (separatorIndex < 0
                    || !"charset".equalsIgnoreCase(parameter.substring(0, separatorIndex).trim())) {
                continue;
            }
            String charsetName = parameter.substring(separatorIndex + 1).trim();
            if (charsetName.length() >= 2 && charsetName.startsWith("\"")
                    && charsetName.endsWith("\"")) {
                charsetName = charsetName.substring(1, charsetName.length() - 1).trim();
            }
            try {
                return Charset.forName(charsetName);
            } catch (IllegalArgumentException ignored) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    List<String[]> parseHeaders(String text) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }

        List<String[]> headers = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            int separatorIndex = line.indexOf(':');
            String name = separatorIndex < 0 ? "" : line.substring(0, separatorIndex).trim();
            if (separatorIndex < 1 || !HEADER_NAME_PATTERN.matcher(name).matches()) {
                throw new IOException("第 " + (i + 1) + " 行请求头格式无效，应为 名称: 值");
            }
            headers.add(new String[]{name, line.substring(separatorIndex + 1).trim()});
        }
        return headers;
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
