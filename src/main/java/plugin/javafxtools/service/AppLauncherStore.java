package plugin.javafxtools.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 启动项工具的本地配置读写。
 *
 * @author wwj
 */
public class AppLauncherStore {
    /**
     * 应用启动项配置文件路径。
     */
    private static final String STORAGE_FILE = "userData/app_launcher_paths.json";

    /**
     * 启动器进程映射持久化文件路径。
     */
    private static final String PROCESS_MAP_FILE = "userData/process_map.json";

    private static final Type APP_INFO_LIST_TYPE = new TypeToken<List<AppInfo>>() {
    }.getType();

    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {
    }.getType();

    private static final Type PROCESS_MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private final Gson gson = new Gson();
    private final ModuleLogger logger;

    /**
     * 创建启动项配置存储。
     *
     * @param logger 日志输出接口
     */
    public AppLauncherStore(ModuleLogger logger) {
        this.logger = logger;
    }

    /**
     * 读取启动器进程映射。
     *
     * @return 进程映射
     */
    public Map<String, String> loadProcessMap() {
        File configFile = new File(PROCESS_MAP_FILE);
        if (!configFile.exists()) {
            return new ConcurrentHashMap<>();
        }

        try (Reader reader = new FileReader(configFile)) {
            Map<String, String> loaded = gson.fromJson(reader, PROCESS_MAP_TYPE);
            if (loaded != null) {
                return new ConcurrentHashMap<>(loaded);
            }
        } catch (Exception e) {
            logger.error("读取进程映射配置失败: " + e.getMessage());
        }
        return new ConcurrentHashMap<>();
    }

    /**
     * 读取应用启动项配置。
     *
     * @param processMap 启动器到实际进程名的映射
     * @return 应用启动项集合
     */
    public List<AppInfo> loadAppInfos(Map<String, String> processMap) {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) {
            return new ArrayList<>();
        }

        try {
            String fileContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            List<AppInfo> savedInfos = gson.fromJson(fileContent, APP_INFO_LIST_TYPE);
            if (savedInfos != null && !savedInfos.isEmpty()) {
                return new ArrayList<>(savedInfos);
            }

            List<String> savedPaths = gson.fromJson(fileContent, STRING_LIST_TYPE);
            if (savedPaths == null) {
                return new ArrayList<>();
            }

            List<AppInfo> appInfos = new ArrayList<>(savedPaths.size());
            for (String path : savedPaths) {
                String launcherName = new File(path).getName().toLowerCase();
                String processName = processMap.getOrDefault(launcherName, launcherName);
                appInfos.add(new AppInfo(path, processName));
            }
            return appInfos;
        } catch (Exception e) {
            logger.error("加载路径失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 保存应用启动项配置。
     *
     * @param appInfos 应用启动项
     */
    public void saveAppInfos(List<AppInfo> appInfos) {
        try (Writer writer = new FileWriter(STORAGE_FILE)) {
            gson.toJson(appInfos, writer);
        } catch (Exception e) {
            logger.error("保存路径失败: " + e.getMessage());
        }
    }
}
