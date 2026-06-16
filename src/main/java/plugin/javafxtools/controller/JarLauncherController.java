package plugin.javafxtools.controller;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import plugin.javafxtools.model.ProjectConfig;
import plugin.javafxtools.service.JarCopyActionService;
import plugin.javafxtools.service.JarFileService;
import plugin.javafxtools.service.JarLauncherUiSupport;
import plugin.javafxtools.service.JarLaunchService;
import plugin.javafxtools.service.JarLauncherRuntimeState;
import plugin.javafxtools.service.JarPortProcessService;
import plugin.javafxtools.service.JarPortQueryService;
import plugin.javafxtools.service.JarProjectActionService;
import plugin.javafxtools.service.JarProjectDialogService;
import plugin.javafxtools.service.JarProjectStore;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JAR 启动器控制器，负责项目配置维护、文件复制、端口检查和 Java 进程启动停止。
 */
public class JarLauncherController {
    @FXML
    private ComboBox<ProjectConfig> projectComboBox;

    @FXML
    private TextField portField;

    @FXML
    private TextField portNumField;

    @FXML
    private TextField profileField;

    @FXML
    private TextArea logArea;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private Button launchButton;

    @FXML
    private Button stopButton;

    private final JarLauncherRuntimeState runtimeState = new JarLauncherRuntimeState();

    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "JarLauncher-Worker");
        t.setDaemon(true);
        return t;
    });

    private final JarProjectStore projectStore = new JarProjectStore(this::appendLog);

    private final JarPortProcessService portProcessService =
            new JarPortProcessService(this::appendLog, this::showError);

    private final JarFileService jarFileService = new JarFileService(this::appendLog);

    private final JarCopyActionService copyActionService =
            new JarCopyActionService(backgroundExecutor, jarFileService,
                    () -> {
                        progressBar.setVisible(true);
                        logArea.clear();
                    },
                    () -> progressBar.setVisible(false),
                    this::appendLog, this::showError, this::updateButtonStates,
                    runtimeState::resolveStatusPort);

    private final JarProjectDialogService projectDialogService = new JarProjectDialogService(this::showError);

    private JarProjectActionService projectActionService;

    private final JarLaunchService jarLaunchService =
            new JarLaunchService(backgroundExecutor, portProcessService, jarFileService,
                    this::appendLog, this::showError, this::confirmKillProcessOnPort,
                    this::updateButtonStates, runtimeState::recordRunningPort,
                    runtimeState::clearRunningPort,
                    disabled -> launchButton.setDisable(disabled),
                    disabled -> stopButton.setDisable(disabled));

    private final JarPortQueryService portQueryService =
            new JarPortQueryService(backgroundExecutor, portProcessService,
                    this::appendLog, this::showError, this::confirmKillProcessOnPort);

    private JarLauncherUiSupport uiSupport;

    /**
     * 初始化 JAR 启动器页签。
     */
    @FXML
    public void initialize() {
        uiSupport = new JarLauncherUiSupport(logArea);
        projectActionService = new JarProjectActionService(projectComboBox, portField, profileField,
                projectStore, projectDialogService, this::appendLog, this::showError,
                this::isProjectRunning, runtimeState::resolveStatusPort,
                runtimeState::clearRunningPort,
                this::updateButtonStates);
        projectActionService.initialize();
    }

    // 处理添加项目操作
    @FXML
    private void handleAddProject(ActionEvent event) {
        projectActionService.addProject();
    }

    // 查询端口占用
    @FXML
    private void queryPort() {
        portQueryService.queryPort(portNumField.getText());
    }

    // 处理编辑项目操作
    @FXML
    private void handleEditProject(ActionEvent event) {
        projectActionService.editProject();
    }

    // 处理复制文件操作
    @FXML
    private void handleCopyAction(ActionEvent event) {
        ProjectConfig selectedProject = getSelectedProject();
        if (selectedProject == null) {
            showError("请先选择项目");
            return;
        }
        copyActionService.copyProjectFiles(selectedProject);
    }

    // 处理启动操作
    @FXML
    private void handleLaunchAction(ActionEvent event) {
        ProjectConfig selectedProject = getSelectedProject();
        if (selectedProject == null) {
            showError("请先选择项目");
            return;
        }

        int port;
        try {
            port = portField.getText().isEmpty() ?
                    selectedProject.getDefaultPort() : Integer.parseInt(portField.getText());
        } catch (NumberFormatException e) {
            showError("端口必须是数字");
            return;
        }
        String profile = profileField.getText().isEmpty() ?
                selectedProject.getDefaultProfile() : profileField.getText();

        // 启动应用
        final int launchPort = port;
        final String launchProfile = profile;
        final ProjectConfig launchProject = selectedProject;
        jarLaunchService.launch(launchProject, launchPort, launchProfile);
    }

    // 处理停止操作
    @FXML
    private void handleStopAction(ActionEvent event) {
        ProjectConfig selectedProject = getSelectedProject();
        if (selectedProject == null) {
            showError("请先选择项目");
            return;
        }
        int port = runtimeState.resolveStopPort(selectedProject, portField.getText());
        final ProjectConfig stopProject = selectedProject;
        final int stopPort = port;
        jarLaunchService.stop(stopProject, stopPort);
    }

    /**
     * 根据项目运行状态更新按钮启用/禁用（使用默认端口）
     */
    private void updateButtonStates(ProjectConfig project) {
        if (project == null) {
            launchButton.setDisable(true);
            stopButton.setDisable(true);
            return;
        }
        updateButtonStates(project, runtimeState.resolveStatusPort(project));
    }

    /**
     * 根据项目运行状态更新按钮启用/禁用（指定端口）
     */
    private void updateButtonStates(ProjectConfig project, int port) {
        if (project == null) {
            launchButton.setDisable(true);
            stopButton.setDisable(true);
            return;
        }
        boolean running = isProjectRunning(project, port);
        launchButton.setDisable(running);
        stopButton.setDisable(!running);
    }

    // 处理删除项目操作
    @FXML
    private void handleDeleteProject(ActionEvent event) {
        projectActionService.deleteProject();
    }

    // 检查项目是否在指定端口上运行（仅依赖端口检测，最可靠）
    private boolean isProjectRunning(ProjectConfig project, int port) {
        return portProcessService.checkPortInUse(port);
    }

    // 确认是否终止端口占用进程，只在 JavaFX 线程中弹出确认框。
    private boolean confirmKillProcessOnPort(int port) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("端口冲突");
        alert.setHeaderText("检测到端口 " + port + " 被占用");
        alert.setContentText("是否终止占用进程？");

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    /**
     * 线程安全地追加 JAR 启动器日志。
     *
     * @param message 日志内容
     */
    private void appendLog(String message) {
        if (uiSupport != null) {
            uiSupport.appendLog(message);
        }
    }

    // 错误提示
    private void showError(String message) {
        if (uiSupport != null) {
            uiSupport.showError(message);
        }
    }

    private ProjectConfig getSelectedProject() {
        return projectActionService != null ? projectActionService.getSelectedProject() : null;
    }

    /**
     * 清理 JAR 启动器后台资源。
     */
    public void cleanup() {
        backgroundExecutor.shutdownNow();
        try {
            if (!backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                appendLog("JAR启动器后台任务未在超时时间内完全停止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
