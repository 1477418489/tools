package plugin.javafxtools.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import plugin.javafxtools.model.ProjectConfig;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * JAR 启动器项目配置的本地 JSON 存储。
 *
 * @author wwj
 */
public class JarProjectStore {
    private static final Path DEFAULT_PROJECTS_CONFIG_FILE =
            AppDataPaths.dataFile("jar_launcher_projects.json");
    private static final Type PROJECT_LIST_TYPE = new TypeToken<List<ProjectConfig>>() {
    }.getType();

    private final Gson gson = new Gson();
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    private final Consumer<String> logger;
    private final Path projectsConfigFile;

    /**
     * 创建 JAR 项目配置存储。
     *
     * @param logger 日志输出回调
     */
    public JarProjectStore(Consumer<String> logger) {
        this(logger, DEFAULT_PROJECTS_CONFIG_FILE);
    }

    JarProjectStore(Consumer<String> logger, Path projectsConfigFile) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.projectsConfigFile = Objects.requireNonNull(projectsConfigFile, "projectsConfigFile");
    }

    /**
     * 读取项目配置。配置文件不存在时返回空配置。
     *
     * @return 以项目 ID 为键的项目配置集合
     */
    public Map<Integer, ProjectConfig> loadProjects() {
        if (!Files.exists(projectsConfigFile)) {
            return new LinkedHashMap<>();
        }

        try {
            List<ProjectConfig> projectList = readStoredProjects();
            Map<Integer, ProjectConfig> projects = toProjectMap(projectList);
            logger.accept("已从JSON文件加载 " + projects.size() + " 个项目配置");
            return projects;
        } catch (Exception e) {
            logger.accept("读取项目配置文件失败: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 保存项目配置。
     *
     * @param projects 项目配置集合
     */
    public boolean saveProjects(Collection<ProjectConfig> projects) {
        try {
            validateCurrentSchema(projects);
            validateExistingFileBeforeOverwrite();
            AtomicFileWriter.writeUtf8(projectsConfigFile,
                    prettyGson.toJson(new ArrayList<>(projects)));
            logger.accept("项目配置已保存到: " + projectsConfigFile);
            return true;
        } catch (Exception e) {
            logger.accept("保存项目配置失败: " + e.getMessage());
            return false;
        }
    }

    private List<ProjectConfig> readStoredProjects() throws IOException {
        try (Reader reader = Files.newBufferedReader(projectsConfigFile, StandardCharsets.UTF_8)) {
            List<ProjectConfig> projectList = gson.fromJson(reader, PROJECT_LIST_TYPE);
            validateCurrentSchema(projectList);
            return projectList;
        }
    }

    private void validateExistingFileBeforeOverwrite() throws IOException {
        if (!Files.exists(projectsConfigFile)) {
            return;
        }
        try {
            readStoredProjects();
        } catch (Exception e) {
            throw new IOException("现有 JAR 项目配置无效，拒绝覆盖", e);
        }
    }

    private void validateCurrentSchema(Collection<ProjectConfig> projects) {
        if (projects == null) {
            throw new IllegalArgumentException("配置根节点必须是数组");
        }

        Set<Integer> projectIds = new HashSet<>();
        for (ProjectConfig project : projects) {
            if (project == null
                    || project.getId() <= 0
                    || !projectIds.add(project.getId())
                    || isBlank(project.getName())
                    || isBlank(project.getSourceJar())
                    || isBlank(project.getTargetJar())
                    || !JarPortProcessService.isValidPort(project.getDefaultPort())) {
                throw new IllegalArgumentException("JAR 项目配置不符合当前格式");
            }

            boolean hasSourceLib = !isBlank(project.getSourceLib());
            boolean hasTargetLib = !isBlank(project.getLibTarget());
            if (hasSourceLib != hasTargetLib) {
                throw new IllegalArgumentException("源 Lib 与目标 Lib 路径必须同时填写");
            }

            Path sourceJar = absolutePath(project.getSourceJar(), "源 JAR 路径");
            Path targetJar = absolutePath(project.getTargetJar(), "目标 JAR 路径");
            if (samePath(sourceJar, targetJar)) {
                throw new IllegalArgumentException("源 JAR 与目标 JAR 路径不能相同");
            }
            if (hasSourceLib) {
                Path sourceLib = absolutePath(project.getSourceLib(), "源 Lib 路径");
                Path targetLib = absolutePath(project.getLibTarget(), "目标 Lib 路径");
                if (samePath(sourceLib, targetLib)
                        || sourceLib.startsWith(targetLib)
                        || targetLib.startsWith(sourceLib)) {
                    throw new IllegalArgumentException("源 Lib 与目标 Lib 路径不能相同或互相包含");
                }
                if (targetJar.startsWith(sourceLib)
                        || targetJar.startsWith(targetLib)
                        || sourceJar.startsWith(targetLib)) {
                    throw new IllegalArgumentException("JAR 路径不能位于会被复制或替换的 Lib 目录中");
                }
            }
        }
    }

    private Path absolutePath(String value, String fieldName) {
        try {
            Path path = Path.of(value).normalize();
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException(fieldName + "必须是绝对路径");
            }
            return path;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(fieldName + "无效", e);
        }
    }

    private boolean samePath(Path first, Path second) {
        return WindowsProcessSupport.isWindows()
                ? first.toString().equalsIgnoreCase(second.toString())
                : first.equals(second);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<Integer, ProjectConfig> toProjectMap(List<ProjectConfig> projectList) {
        Map<Integer, ProjectConfig> projects = new LinkedHashMap<>();
        if (projectList != null) {
            for (ProjectConfig project : projectList) {
                if (project != null) {
                    projects.put(project.getId(), project);
                }
            }
        }
        return projects;
    }
}
