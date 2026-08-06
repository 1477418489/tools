package plugin.javafxtools.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import plugin.javafxtools.model.HttpTemplate;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;
import plugin.javafxtools.util.HttpUrlSupport;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * HTTP 请求模板的本地 JSON 存储。
 *
 * @author wwj
 */
public class HttpTemplateStore {
    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private static final Path DEFAULT_TEMPLATE_FILE = AppDataPaths.dataFile("http_templates.json");
    private static final Type TEMPLATE_MAP_TYPE = new TypeToken<Map<String, HttpTemplate>>() {
    }.getType();

    private final Gson gson = new Gson();
    private final Path templateFile;

    public HttpTemplateStore() {
        this(DEFAULT_TEMPLATE_FILE);
    }

    HttpTemplateStore(Path templateFile) {
        this.templateFile = Objects.requireNonNull(templateFile, "templateFile");
    }

    /**
     * 读取请求模板。
     *
     * @return 以模板名为键的模板集合
     */
    public Map<String, HttpTemplate> loadTemplates() {
        if (!Files.exists(templateFile)) {
            return new HashMap<>();
        }

        try {
            return new HashMap<>(readStoredTemplates());
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
        try {
            validateCurrentSchema(templates);
            validateExistingFileBeforeOverwrite();
            AtomicFileWriter.writeUtf8(templateFile,
                    gson.toJson(templates));
        } catch (Exception e) {
            throw new IllegalStateException("保存模板失败: " + e.getMessage(), e);
        }
    }

    private Map<String, HttpTemplate> readStoredTemplates() throws IOException {
        try (Reader reader = Files.newBufferedReader(templateFile, StandardCharsets.UTF_8)) {
            Map<String, HttpTemplate> templates = gson.fromJson(reader, TEMPLATE_MAP_TYPE);
            validateCurrentSchema(templates);
            return templates;
        }
    }

    private void validateExistingFileBeforeOverwrite() throws IOException {
        if (!Files.exists(templateFile)) {
            return;
        }
        try {
            readStoredTemplates();
        } catch (Exception e) {
            throw new IOException("现有 HTTP 模板配置无效，拒绝覆盖", e);
        }
    }

    private void validateCurrentSchema(Map<String, HttpTemplate> templates) {
        if (templates == null) {
            throw new IllegalArgumentException("配置根节点必须是对象");
        }

        templates.forEach((name, template) -> {
            if (name == null
                    || name.isBlank()
                    || !name.equals(name.trim())
                    || template == null
                    || !HttpUrlSupport.isValid(template.url)
                    || !SUPPORTED_METHODS.contains(template.method)
                    || template.params == null
                    || template.headers == null
                    || !isPositiveLong(template.interval)
                    || !isPositiveInt(template.connectTimeout)
                    || !isPositiveInt(template.readTimeout)) {
                throw new IllegalArgumentException("HTTP 模板不符合当前格式: " + name);
            }
        });
    }

    private boolean isPositiveLong(String value) {
        try {
            return value != null && Long.parseLong(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isPositiveInt(String value) {
        try {
            return value != null && Integer.parseInt(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
