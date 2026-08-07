package plugin.javafxtools.model;

/**
 * HTTP 请求执行结果。
 *
 * @param statusCode HTTP 状态码
 * @param elapsedMillis 请求耗时（毫秒）
 * @param responseHeaders 响应头文本
 * @param rawBody 原始响应体
 * @author wwj
 */
public record HttpRequestResult(int statusCode,
                                long elapsedMillis,
                                String responseHeaders,
                                String rawBody) {
}
