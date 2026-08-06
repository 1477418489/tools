package plugin.javafxtools.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final Path DEFAULT_STORAGE_FILE = AppDataPaths.dataFile("app_launcher_paths.json");

    private static final String PROCESS_MAP_RESOURCE = "/plugin/javafxtools/process-map.json";

    private static final Type APP_INFO_LIST_TYPE = new TypeToken<List<AppInfo>>() {
    }.getType();

    private static final Type PROCESS_MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private final Gson gson = new Gson();
    private final ModuleLogger logger;
    private final Path storageFile;

    /**
     * 创建启动项配置存储。
     *
     * @param logger 日志输出接口
     */
    public AppLauncherStore(ModuleLogger logger) {
        this(logger, DEFAULT_STORAGE_FILE);
    }

    AppLauncherStore(ModuleLogger logger, Path storageFile) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.storageFile = Objects.requireNonNull(storageFile, "storageFile");
    }

    /**
     * 读取启动器进程映射。
     *
     * @return 进程映射
     */
    public Map<String, String> loadProcessMap() {
        InputStream resource = AppLauncherStore.class.getResourceAsStream(PROCESS_MAP_RESOURCE);
        if (resource == null) {
            logger.error("缺少内置进程映射资源: " + PROCESS_MAP_RESOURCE);
            return new ConcurrentHashMap<>();
        }

        try (Reader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            Map<String, String> loaded = gson.fromJson(reader, PROCESS_MAP_TYPE);
            ConcurrentHashMap<String, String> normalized = new ConcurrentHashMap<>();
            if (loaded != null) {
                loaded.forEach((launcher, process) -> {
                    if (launcher != null && process != null) {
                        normalized.put(launcher.toLowerCase(Locale.ROOT), process);
                    }
                });
            }
            return normalized;
        } catch (Exception e) {
            logger.error("读取进程映射配置失败: " + e.getMessage());
        }
        return new ConcurrentHashMap<>();
    }

    /**
     * 读取应用启动项配置。
     *
     * @return 应用启动项集合
     */
    public List<AppInfo> loadAppInfos() {
        if (!Files.exists(storageFile)) {
            return new ArrayList<>();
        }

        try {
            return new ArrayList<>(readStoredAppInfos());
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
    public boolean saveAppInfos(List<AppInfo> appInfos) {
        try {
            validateCurrentSchema(appInfos);
            validateExistingFileBeforeOverwrite();
            AtomicFileWriter.writeUtf8(storageFile,
                    gson.toJson(appInfos));
            return true;
        } catch (Exception e) {
            logger.error("保存路径失败: " + e.getMessage());
            return false;
        }
    }

    private List<AppInfo> readStoredAppInfos() throws IOException {
        String fileContent = Files.readString(storageFile, StandardCharsets.UTF_8);
        List<AppInfo> savedInfos = gson.fromJson(fileContent, APP_INFO_LIST_TYPE);
        validateCurrentSchema(savedInfos);
        return savedInfos;
    }

    private void validateExistingFileBeforeOverwrite() throws IOException {
        if (!Files.exists(storageFile)) {
            return;
        }
        try {
            readStoredAppInfos();
        } catch (Exception e) {
            throw new IOException("现有启动项配置无效，拒绝覆盖", e);
        }
    }

    private void validateCurrentSchema(List<AppInfo> appInfos) {
        if (appInfos == null) {
            throw new IllegalArgumentException("配置根节点必须是数组");
        }

        Set<String> paths = new HashSet<>();
        for (AppInfo appInfo : appInfos) {
            if (appInfo == null
                    || appInfo.getAppPath() == null
                    || appInfo.getAppPath().isBlank()
                    || appInfo.getProcessName() == null
                    || appInfo.getProcessName().isBlank()) {
                throw new IllegalArgumentException("启动项配置不符合当前格式");
            }
            try {
                Path path = Path.of(appInfo.getAppPath());
                if (!path.isAbsolute()) {
                    throw new IllegalArgumentException("启动项路径必须是绝对路径");
                }
                String normalized = path.normalize().toString();
                String pathKey = WindowsProcessSupport.isWindows()
                        ? normalized.toLowerCase(Locale.ROOT)
                        : normalized;
                if (!paths.add(pathKey)) {
                    throw new IllegalArgumentException("启动项路径不能重复");
                }
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("启动项路径无效", e);
            }
        }
    }
}
