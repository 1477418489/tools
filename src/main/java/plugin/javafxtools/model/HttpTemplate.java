package plugin.javafxtools.model;

/**
 * HTTP 请求页签保存的请求模板数据结构。
 */
public class HttpTemplate {
    /**
     * 请求地址。
     */
    public String url;

    /**
     * HTTP 请求方法。
     */
    public String method;

    /**
     * 请求参数文本。
     */
    public String params;

    /**
     * 请求头文本。
     */
    public String headers;

    /**
     * 定时请求间隔。
     */
    public String interval;

    /**
     * 连接超时时间。
     */
    public String connectTimeout;

    /**
     * 读取超时时间。
     */
    public String readTimeout;

    /**
     * 供 Gson 反序列化使用的无参构造方法。
     */
    public HttpTemplate() {
    }

    /**
     * 创建 HTTP 请求模板。
     *
     * @param url 请求地址
     * @param method HTTP 方法
     * @param params 请求参数
     * @param headers 请求头
     * @param interval 请求间隔
     * @param connectTimeout 连接超时
     * @param readTimeout 读取超时
     */
    public HttpTemplate(String url, String method, String params, String headers,
                        String interval, String connectTimeout, String readTimeout) {
        this.url = url;
        this.method = method;
        this.params = params;
        this.headers = headers;
        this.interval = interval;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }
}
