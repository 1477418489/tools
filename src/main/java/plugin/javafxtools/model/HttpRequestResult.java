package plugin.javafxtools.model;

/**
 * HTTP 请求执行结果。
 *
 * @param logContent 响应状态和响应头日志内容
 * @param rawBody 原始响应体
 * @author wwj
 */
public record HttpRequestResult(String logContent, String rawBody) {
}
