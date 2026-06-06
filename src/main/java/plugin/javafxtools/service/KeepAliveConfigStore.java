package plugin.javafxtools.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import plugin.javafxtools.model.KeepAliveConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 域名保活配置的本地 JSON 存储。
 *
 * @author wwj
 */
public class KeepAliveConfigStore {
    private static final String CONFIG_FILE = "userData/keepAlive.json";
    private static final TypeReference<List<KeepAliveConfig>> CONFIG_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    /**
     * 读取保活配置。配置文件不存在时会创建空配置文件。
     *
     * @return 保活配置列表
     * @throws IOException 文件读取失败
     */
    public List<KeepAliveConfig> loadConfigs() throws IOException {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            saveEmptyConfigFileQuietly();
            return new ArrayList<>();
        }

        if (configFile.length() == 0) {
            return new ArrayList<>();
        }

        try {
            List<KeepAliveConfig> configs = mapper.readValue(configFile, CONFIG_LIST_TYPE);
            return configs == null ? new ArrayList<>() : configs;
        } catch (Exception e) {
            backupBrokenConfig(configFile);
            saveEmptyConfigFileQuietly();
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
        File configFile = ensureConfigDirectoryExists();
        mapper.writeValue(configFile, configs == null ? new ArrayList<>() : configs);
    }

    private void saveEmptyConfigFileQuietly() {
        try {
            saveConfigs(new ArrayList<>());
        } catch (IOException e) {
            // Preserve the old behavior: startup can continue with an empty in-memory list.
        }
    }

    private void backupBrokenConfig(File configFile) {
        try {
            if (!configFile.exists()) {
                return;
            }
            String backupName = "keepAlive_backup_" + System.currentTimeMillis() + ".json";
            File backupFile = new File(configFile.getParentFile(), backupName);
            Files.copy(configFile.toPath(), backupFile.toPath());
        } catch (IOException e) {
            // Backup failure should not prevent recovering to an empty config file.
        }
    }

    private File ensureConfigDirectoryExists() {
        File configFile = new File(CONFIG_FILE);
        File parent = configFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        return configFile;
    }
}
