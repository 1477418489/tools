package plugin.javafxtools.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

/**
 * HTTP 响应体格式化。
 *
 * @author wwj
 */
public class HttpResponseFormatter {
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 根据响应格式配置生成展示用响应体。
     *
     * @param rawBody 原始响应体
     * @param responseFormat 响应格式配置
     * @return 展示用响应体
     */
    public String formatForPreview(String rawBody, String responseFormat) {
        String displayBody = rawBody;
        if ("Pretty JSON".equalsIgnoreCase(responseFormat)
                || ("Auto".equalsIgnoreCase(responseFormat) && isJson(rawBody))) {
            String pretty = tryPrettyJson(rawBody);
            if (pretty != null) {
                displayBody = pretty;
            }
        }
        return displayBody;
    }

    /**
     * 尝试美化 JSON 字符串。
     *
     * @param json 原始 JSON
     * @return 美化后的 JSON，失败时为 null
     */
    public String tryPrettyJson(String json) {
        try {
            return PRETTY_GSON.toJson(JsonParser.parseString(json));
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isJson(String str) {
        if (str == null) {
            return false;
        }
        String s = str.trim();
        return (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"));
    }
}
