package plugin.javafxtools.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import plugin.javafxtools.model.ProjectConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * JAR 启动器项目配置的本地 JSON 存储。
 *
 * @author wwj
 */
public class JarProjectStore {
    private static final String PROJECTS_CONFIG_FILE = "userData/jar_launcher_projects.json";
    private static final Type PROJECT_LIST_TYPE = new TypeToken<List<ProjectConfig>>() {
    }.getType();

    private final Gson gson = new Gson();
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    private final Consumer<String> logger;

    /**
     * 创建 JAR 项目配置存储。
     *
     * @param logger 日志输出回调
     */
    public JarProjectStore(Consumer<String> logger) {
        this.logger = logger;
    }

    /**
     * 读取项目配置。配置文件不存在时会初始化默认配置并保存。
     *
     * @return 以项目 ID 为键的项目配置集合
     */
    public Map<Integer, ProjectConfig> loadProjects() {
        File configFile = new File(PROJECTS_CONFIG_FILE);
        if (!configFile.exists()) {
            Map<Integer, ProjectConfig> defaults = defaultProjects();
            saveProjects(defaults.values());
            return defaults;
        }

        try (Reader reader = new FileReader(configFile)) {
            List<ProjectConfig> projectList = gson.fromJson(reader, PROJECT_LIST_TYPE);
            Map<Integer, ProjectConfig> projects = toProjectMap(projectList);
            logger.accept("已从JSON文件加载 " + projects.size() + " 个项目配置");
            return projects;
        } catch (Exception e) {
            logger.accept("读取项目配置文件失败: " + e.getMessage());
            return defaultProjects();
        }
    }

    /**
     * 保存项目配置。
     *
     * @param projects 项目配置集合
     */
    public void saveProjects(Collection<ProjectConfig> projects) {
        try (Writer writer = new FileWriter(PROJECTS_CONFIG_FILE)) {
            prettyGson.toJson(new ArrayList<>(projects), writer);
            logger.accept("项目配置已保存到: " + PROJECTS_CONFIG_FILE);
        } catch (Exception e) {
            logger.accept("保存项目配置失败: " + e.getMessage());
        }
    }

    private Map<Integer, ProjectConfig> toProjectMap(List<ProjectConfig> projectList) {
        Map<Integer, ProjectConfig> projects = new HashMap<>();
        if (projectList != null) {
            for (ProjectConfig project : projectList) {
                projects.put(project.getId(), project);
            }
        }
        return projects;
    }

    private Map<Integer, ProjectConfig> defaultProjects() {
        Map<Integer, ProjectConfig> projects = new HashMap<>();

        ProjectConfig jza = new ProjectConfig();
        jza.setId(1);
        jza.setName("jza项目");
        jza.setSourceJar("D:\\qnIdea\\zhian-fire-monitor\\zhian-admin\\target\\jza.jar");
        jza.setTargetJar("D:\\test\\jar\\jza.jar");
        jza.setSourceLib("D:\\qnIdea\\zhian-fire-monitor\\zhian-admin\\target\\lib");
        jza.setLibTarget("D:\\test\\jar\\lib");
        jza.setDefaultPort(9101);
        jza.setDefaultProfile("local");
        jza.setJvmOpts("-Xms512m -Xmx1024m -Dfile.encoding=UTF-8");
        jza.setOtherOpts("--zhian.basic.path=D:\\qnIdea\\zhstatic");
        projects.put(1, jza);

        logger.accept("已初始化默认项目配置");
        return projects;
    }
}
