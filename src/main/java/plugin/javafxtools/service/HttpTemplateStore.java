package plugin.javafxtools.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import plugin.javafxtools.model.HttpTemplate;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 请求模板的本地 JSON 存储。
 *
 * @author wwj
 */
public class HttpTemplateStore {
    private static final String TEMPLATE_FILE = "http_templates.json";
    private static final Type TEMPLATE_MAP_TYPE = new TypeToken<Map<String, HttpTemplate>>() {
    }.getType();

    private final Gson gson = new Gson();

    /**
     * 读取请求模板。
     *
     * @return 以模板名为键的模板集合
     */
    public Map<String, HttpTemplate> loadTemplates() {
        File file = new File(TEMPLATE_FILE);
        if (!file.exists()) {
            return new HashMap<>();
        }

        try (Reader reader = new FileReader(file)) {
            Map<String, HttpTemplate> templates = gson.fromJson(reader, TEMPLATE_MAP_TYPE);
            return templates == null ? new HashMap<>() : new HashMap<>(templates);
        } catch (Exception e) {
            throw new IllegalStateException("加载模板失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存请求模板。
     *
     * @param templates 模板集合
     */
    public void saveTemplates(Map<String, HttpTemplate> templates) {
        try (Writer writer = new FileWriter(TEMPLATE_FILE)) {
            gson.toJson(templates, writer);
        } catch (Exception e) {
            throw new IllegalStateException("保存模板失败: " + e.getMessage(), e);
        }
    }
}
