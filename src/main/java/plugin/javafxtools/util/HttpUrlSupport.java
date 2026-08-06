package plugin.javafxtools.util;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Locale;

/**
 * HTTP/HTTPS 地址解析与校验。
 */
public final class HttpUrlSupport {
    private HttpUrlSupport() {
    }

    /**
     * 将字符串解析为带主机名的 HTTP/HTTPS URI。
     *
     * @param value 地址文本
     * @return 已校验的 URI
     * @throws IllegalArgumentException 地址无效或协议不受支持
     */
    public static URI parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("地址不能为空");
        }

        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (scheme == null
                || !("http".equals(scheme.toLowerCase(Locale.ROOT))
                || "https".equals(scheme.toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("仅支持 http 或 https 协议");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("地址缺少有效主机名");
        }
        return uri;
    }

    /**
     * 将地址转换为 URL，并将解析错误统一为 IOException。
     *
     * @param value 地址文本
     * @return 已校验的 URL
     * @throws IOException 地址无效
     */
    public static URL toUrl(String value) throws IOException {
        try {
            return parse(value).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("无效HTTP地址: " + value, e);
        }
    }

    /**
     * 判断地址是否符合当前 HTTP/HTTPS 输入规则。
     *
     * @param value 地址文本
     * @return 是否有效
     */
    public static boolean isValid(String value) {
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
