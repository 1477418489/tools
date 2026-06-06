package plugin.javafxtools.model;

/**
 * HTTP 定时请求启动配置。
 *
 * @param startTimeText 开始时间文本
 * @param intervalText 间隔秒数文本
 * @param url 请求地址
 * @param method HTTP 方法
 * @param params 请求参数或请求体
 * @param headers 请求头文本
 * @param connectTimeoutText 连接超时文本
 * @param readTimeoutText 读取超时文本
 * @param responseFormat 响应格式配置
 * @author wwj
 */
public record HttpScheduleConfig(
        String startTimeText,
        String intervalText,
        String url,
        String method,
        String params,
        String headers,
        String connectTimeoutText,
        String readTimeoutText,
        String responseFormat
) {
}
