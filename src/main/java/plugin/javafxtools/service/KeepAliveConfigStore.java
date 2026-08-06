package plugin.javafxtools.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import plugin.javafxtools.model.KeepAliveConfig;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;
import plugin.javafxtools.util.HttpUrlSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 域名保活配置的本地 JSON 存储。
 *
 * @author wwj
 */
public class KeepAliveConfigStore {
    private static final Path DEFAULT_CONFIG_FILE = AppDataPaths.dataFile("keepAlive.json");
    private static final TypeReference<List<KeepAliveConfig>> CONFIG_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.INDENT_OUTPUT, true);
    private final Path configFile;

    public KeepAliveConfigStore() {
        this(DEFAULT_CONFIG_FILE);
    }

    KeepAliveConfigStore(Path configFile) {
        this.configFile = Objects.requireNonNull(configFile, "configFile");
    }

    /**
     * 读取保活配置。配置文件不存在时返回空配置。
     *
     * @return 保活配置列表
     * @throws IOException 文件读取失败
     */
    public List<KeepAliveConfig> loadConfigs() throws IOException {
        if (!Files.exists(configFile)) {
            return new ArrayList<>();
        }

        try {
            return new ArrayList<>(readStoredConfigs());
        } catch (Exception e) {
            throw new IOException("加载配置文件失败", e);
        }
    }

    /**
     * 保存保活配置。
     *
     * @param configs 配置列表
     * @throws IOException 文件写入失败
     */
    public void saveConfigs(List<KeepAliveConfig> configs) throws IOException {
        validateCurrentSchema(configs);
        validateExistingFileBeforeOverwrite();
        AtomicFileWriter.writeUtf8(configFile, mapper.writeValueAsString(configs));
    }

    private List<KeepAliveConfig> readStoredConfigs() throws IOException {
        List<KeepAliveConfig> configs = mapper.readValue(configFile.toFile(), CONFIG_LIST_TYPE);
        validateCurrentSchema(configs);
        return configs;
    }

    private void validateExistingFileBeforeOverwrite() throws IOException {
        if (!Files.exists(configFile)) {
            return;
        }
        try {
            readStoredConfigs();
        } catch (Exception e) {
            throw new IOException("现有保活配置无效，拒绝覆盖", e);
        }
    }

    private void validateCurrentSchema(List<KeepAliveConfig> configs) throws IOException {
        if (configs == null) {
            throw new IOException("配置根节点必须是数组");
        }
        Set<String> domains = new HashSet<>();
        for (KeepAliveConfig config : configs) {
            if (config == null
                    || !HttpUrlSupport.isValid(config.getDomain())
                    || config.getMethod() == null
                    || config.getUnit() == null
                    || config.getMinInterval() <= 0
                    || config.getMaxInterval() < config.getMinInterval()
                    || config.getMaxInterval() > 1000
                    || !domains.add(config.getDomain())) {
                throw new IOException("保活配置不符合当前格式");
            }
        }
    }
}
