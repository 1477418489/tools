package plugin.javafxtools.service;

import javafx.collections.FXCollections;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import plugin.javafxtools.model.ProjectConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * JAR 启动器项目列表的加载、增删改和选择状态维护。
 *
 * @author wwj
 */
public class JarProjectActionService {
    private final ComboBox<ProjectConfig> projectComboBox;
    private final TextField portField;
    private final TextField profileField;
    private final JarProjectStore projectStore;
    private final JarProjectDialogService projectDialogService;
    private final Consumer<String> logger;
    private final Consumer<String> errorReporter;
    private final BiPredicate<ProjectConfig, Integer> projectRunningChecker;
    private final ToIntFunction<ProjectConfig> statusPortResolver;
    private final Consumer<ProjectConfig> runningPortClearer;
    private final Consumer<ProjectConfig> buttonStateUpdater;
    private final Map<Integer, ProjectConfig> projects = new LinkedHashMap<>();

    private ProjectConfig selectedProject;

    /**
     * 创建项目操作服务。
     *
     * @param projectComboBox 项目下拉框
     * @param portField 端口输入框
     * @param profileField Spring profile 输入框
     * @param projectStore 项目配置存储
     * @param projectDialogService 项目配置弹窗
     * @param logger 日志输出回调
     * @param errorReporter 错误提示回调
     * @param projectRunningChecker 项目运行状态检查回调
     * @param statusPortResolver 项目状态端口读取回调
     * @param runningPortClearer 项目运行端口清除回调
     * @param buttonStateUpdater 按钮状态刷新回调
     */
    public JarProjectActionService(ComboBox<ProjectConfig> projectComboBox,
                                   TextField portField,
                                   TextField profileField,
                                   JarProjectStore projectStore,
                                   JarProjectDialogService projectDialogService,
                                   Consumer<String> logger,
                                   Consumer<String> errorReporter,
                                   BiPredicate<ProjectConfig, Integer> projectRunningChecker,
                                   ToIntFunction<ProjectConfig> statusPortResolver,
                                   Consumer<ProjectConfig> runningPortClearer,
                                   Consumer<ProjectConfig> buttonStateUpdater) {
        this.projectComboBox = projectComboBox;
        this.portField = portField;
        this.profileField = profileField;
        this.projectStore = projectStore;
        this.projectDialogService = projectDialogService;
        this.logger = logger;
        this.errorReporter = errorReporter;
        this.projectRunningChecker = projectRunningChecker;
        this.statusPortResolver = statusPortResolver;
        this.runningPortClearer = runningPortClearer;
        this.buttonStateUpdater = buttonStateUpdater;
    }

    /**
     * 加载项目配置并绑定下拉框选择监听。
     */
    public void initialize() {
        projectComboBox.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldVal, newVal) -> selectProject(newVal));
        loadProjectsFromJson();
        refreshProjectItems();
        if (projectComboBox.getItems().isEmpty()) {
            selectProject(null);
        } else {
            projectComboBox.getSelectionModel().selectFirst();
        }
    }

    /**
     * 获取当前选中的项目配置。
     *
     * @return 当前选中项目，未选择时为 null
     */
    public ProjectConfig getSelectedProject() {
        return selectedProject;
    }

    /**
     * 添加项目。
     */
    public void addProject() {
        ProjectConfig newProject = projectDialogService.showProjectConfigDialog(new ProjectConfig());
        if (newProject == null) {
            return;
        }

        int maxId = projects.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        newProject.setId(maxId + 1);
        Map<Integer, ProjectConfig> updatedProjects = new LinkedHashMap<>(projects);
        updatedProjects.put(newProject.getId(), newProject);
        if (!commitProjects(updatedProjects)) {
            return;
        }
        refreshProjectItems();
        projectComboBox.getSelectionModel().select(newProject);
        logger.accept("已添加新项目: " + newProject.getName());
    }

    /**
     * 编辑当前选中的项目。
     */
    public void editProject() {
        if (selectedProject == null) {
            errorReporter.accept("请先选择要编辑的项目");
            return;
        }

        ProjectConfig editedProject =
                projectDialogService.showProjectConfigDialog(new ProjectConfig(selectedProject));
        if (editedProject == null) {
            return;
        }

        editedProject.setId(selectedProject.getId());
        Map<Integer, ProjectConfig> updatedProjects = new LinkedHashMap<>(projects);
        updatedProjects.put(editedProject.getId(), editedProject);
        if (!commitProjects(updatedProjects)) {
            return;
        }
        refreshProjectItems();
        projectComboBox.getSelectionModel().select(editedProject);
        logger.accept("已更新项目: " + editedProject.getName());
    }

    /**
     * 删除当前选中的项目。
     */
    public void deleteProject() {
        if (selectedProject == null) {
            errorReporter.accept("请先选择要删除的项目");
            return;
        }

        ProjectConfig projectToDelete = selectedProject;
        if (isSelectedProjectRunning(projectToDelete) && !confirmDeleteRunningProject(projectToDelete)) {
            logger.accept("用户取消了删除操作: " + projectToDelete.getName());
            return;
        }

        if (!confirmDeleteProject(projectToDelete)) {
            return;
        }

        Map<Integer, ProjectConfig> updatedProjects = new LinkedHashMap<>(projects);
        updatedProjects.remove(projectToDelete.getId());
        if (!commitProjects(updatedProjects)) {
            return;
        }
        refreshProjectItems();
        projectComboBox.getSelectionModel().clearSelection();
        selectedProject = null;
        runningPortClearer.accept(projectToDelete);
        portField.clear();
        profileField.clear();
        buttonStateUpdater.accept(null);
        logger.accept("已删除项目: " + projectToDelete.getName());
    }

    private void loadProjectsFromJson() {
        projects.clear();
        projects.putAll(projectStore.loadProjects());
    }

    private boolean commitProjects(Map<Integer, ProjectConfig> updatedProjects) {
        if (!projectStore.saveProjects(updatedProjects.values())) {
            errorReporter.accept("项目配置保存失败，界面未做更改");
            return false;
        }
        projects.clear();
        projects.putAll(updatedProjects);
        return true;
    }

    private void refreshProjectItems() {
        projectComboBox.setItems(FXCollections.observableArrayList(projects.values()));
    }

    private void selectProject(ProjectConfig project) {
        selectedProject = project;
        if (project == null) {
            buttonStateUpdater.accept(null);
            return;
        }

        portField.setText(String.valueOf(project.getDefaultPort()));
        profileField.setText(project.getDefaultProfile());
        buttonStateUpdater.accept(project);
    }

    private boolean isSelectedProjectRunning(ProjectConfig project) {
        int port = statusPortResolver.applyAsInt(project);
        return projectRunningChecker.test(project, port);
    }

    private boolean confirmDeleteRunningProject(ProjectConfig project) {
        Alert runningAlert = new Alert(Alert.AlertType.WARNING);
        runningAlert.setTitle("项目正在运行");
        runningAlert.setHeaderText("项目 \"" + project.getName() + "\" 正在运行");
        runningAlert.setContentText("建议先停止项目再删除，否则可能导致数据丢失。\n\n是否仍要继续删除？");

        Optional<ButtonType> result = runningAlert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private boolean confirmDeleteProject(ProjectConfig project) {
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("确认删除");
        confirmAlert.setHeaderText("确定要删除项目 \"" + project.getName() + "\" 吗？");
        confirmAlert.setContentText("此操作不可撤销，项目配置将被永久删除。");

        Optional<ButtonType> result = confirmAlert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }
}
