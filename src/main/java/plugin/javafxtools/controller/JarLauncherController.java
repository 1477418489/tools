package plugin.javafxtools.controller;

import javafx.application.Platform;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    @FXML
    private Button copyButton;

    @FXML
    private Button addProjectButton;

    @FXML
    private Button editProjectButton;

    @FXML
    private Button deleteProjectButton;

    @FXML
    private Button portQueryButton;

    @FXML
    private Label projectStatusLabel;

    private final JarLauncherRuntimeState runtimeState = new JarLauncherRuntimeState();

    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "JarLauncher-Worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong statusCheckVersion = new AtomicLong();

    private final AtomicReference<ProjectOperation> activeOperation =
            new AtomicReference<>(ProjectOperation.NONE);

    private final JarProjectStore projectStore = new JarProjectStore(this::appendLog);

    private final JarPortProcessService portProcessService =
            new JarPortProcessService(this::appendLog, this::showError);

    private final JarFileService jarFileService = new JarFileService(this::appendLog);

    private final JarCopyActionService copyActionService =
            new JarCopyActionService(backgroundExecutor, jarFileService,
                    () -> {
                        progressBar.setVisible(true);
                        progressBar.setManaged(true);
                        logArea.clear();
                    },
                    () -> {
                        progressBar.setVisible(false);
                        progressBar.setManaged(false);
                    },
                    this::appendLog, this::showError, this::finishProjectOperation,
                    runtimeState::resolveStatusPort);

    private final JarProjectDialogService projectDialogService = new JarProjectDialogService(this::showError);

    private JarProjectActionService projectActionService;

    private final JarLaunchService jarLaunchService =
            new JarLaunchService(backgroundExecutor, portProcessService, jarFileService,
                    this::appendLog, this::showError, this::confirmKillProcessOnPort,
                    this::finishProjectOperation, runtimeState::recordRunningPort,
                    runtimeState::clearRunningPort);

    private final JarPortQueryService portQueryService =
            new JarPortQueryService(backgroundExecutor, portProcessService,
                    this::appendLog, this::showError, this::confirmKillProcessOnPort,
                    () -> finishProjectOperation(null, -1));

    private JarLauncherUiSupport uiSupport;

    /**
     * 初始化 JAR 启动器页签。
     */
    @FXML
    public void initialize() {
        uiSupport = new JarLauncherUiSupport(logArea);
        applyProjectState(null, false);
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
        if (!projectConfigurationAvailable()) {
            return;
        }
        projectActionService.addProject();
    }

    // 查询端口占用
    @FXML
    private void queryPort() {
        String portText = portNumField.getText() == null ? "" : portNumField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            showError(portText.isEmpty() ? "请输入要查询的端口" : "端口必须是数字");
            return;
        }
        if (!JarPortProcessService.isValidPort(port)) {
            showError("端口必须在 1 到 65535 之间");
            return;
        }
        if (!beginProjectOperation(ProjectOperation.QUERYING)) {
            return;
        }
        portQueryService.queryPort(port);
    }

    // 处理编辑项目操作
    @FXML
    private void handleEditProject(ActionEvent event) {
        if (!projectConfigurationAvailable()) {
            return;
        }
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
        if (!beginProjectOperation(ProjectOperation.COPYING)) {
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
        String portText = portField.getText() == null ? "" : portField.getText().trim();
        try {
            port = portText.isEmpty()
                    ? selectedProject.getDefaultPort()
                    : Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            showError("端口必须是数字");
            return;
        }
        if (!JarPortProcessService.isValidPort(port)) {
            showError("端口必须在 1 到 65535 之间");
            return;
        }
        String profileText = profileField.getText() == null ? "" : profileField.getText().trim();
        String profile = profileText.isEmpty() ? selectedProject.getDefaultProfile() : profileText;
        if (!beginProjectOperation(ProjectOperation.LAUNCHING)) {
            return;
        }

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
        if (!JarPortProcessService.isValidPort(port)) {
            showError("端口必须在 1 到 65535 之间");
            return;
        }
        if (!beginProjectOperation(ProjectOperation.STOPPING)) {
            return;
        }
        final ProjectConfig stopProject = selectedProject;
        final int stopPort = port;
        jarLaunchService.stop(stopProject, stopPort);
    }

    /**
     * 根据项目运行状态更新按钮启用/禁用（使用默认端口）
     */
    private void updateButtonStates(ProjectConfig project) {
        if (project == null) {
            statusCheckVersion.incrementAndGet();
            applyProjectState(null, false);
            return;
        }
        scheduleProjectStateCheck(project, runtimeState.resolveStatusPort(project));
    }

    /**
     * 根据项目运行状态更新按钮启用/禁用（指定端口）
     */
    private void updateButtonStates(ProjectConfig project, int port) {
        if (project == null) {
            statusCheckVersion.incrementAndGet();
            applyProjectState(null, false);
            return;
        }
        scheduleProjectStateCheck(project, port);
    }

    // 处理删除项目操作
    @FXML
    private void handleDeleteProject(ActionEvent event) {
        if (!projectConfigurationAvailable()) {
            return;
        }
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
        setProjectStatus(message, "ERROR");
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
        statusCheckVersion.incrementAndGet();
        backgroundExecutor.shutdownNow();
        try {
            if (!backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                appendLog("JAR启动器后台任务未在超时时间内完全停止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 清空当前执行日志。
     */
    @FXML
    private void handleClearLog() {
        logArea.clear();
    }

    private void scheduleProjectStateCheck(ProjectConfig project, int port) {
        ProjectOperation operation = activeOperation.get();
        if (operation != ProjectOperation.NONE) {
            statusCheckVersion.incrementAndGet();
            applyBusyState(operation);
            return;
        }
        long version = statusCheckVersion.incrementAndGet();
        launchButton.setDisable(true);
        stopButton.setDisable(true);
        copyButton.setDisable(true);
        setProjectStatus("检查中", "BUSY");

        try {
            backgroundExecutor.submit(() -> {
                try {
                    boolean running = isProjectRunning(project, port);
                    Platform.runLater(() -> {
                        ProjectConfig selected = getSelectedProject();
                        if (statusCheckVersion.get() != version
                                || selected == null
                                || selected.getId() != project.getId()) {
                            return;
                        }
                        applyProjectState(project, running);
                    });
                } catch (RuntimeException e) {
                    Platform.runLater(() -> {
                        if (statusCheckVersion.get() != version) {
                            return;
                        }
                        applyProjectState(project, false);
                        showError("项目状态检查失败: " + e.getMessage());
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            applyProjectState(project, false);
        }
    }

    private void applyProjectState(ProjectConfig project, boolean running) {
        ProjectOperation operation = activeOperation.get();
        if (operation != ProjectOperation.NONE) {
            applyBusyState(operation);
            return;
        }
        boolean noProject = project == null;
        projectComboBox.setDisable(false);
        portField.setDisable(noProject);
        profileField.setDisable(noProject);
        portNumField.setDisable(false);
        portQueryButton.setDisable(false);
        addProjectButton.setDisable(false);
        editProjectButton.setDisable(noProject);
        deleteProjectButton.setDisable(noProject);
        launchButton.setDisable(noProject || running);
        stopButton.setDisable(noProject || !running);
        copyButton.setDisable(noProject || running || progressBar.isVisible());
        if (noProject) {
            setProjectStatus("未选择项目", "OFFLINE");
        } else if (running) {
            setProjectStatus("运行中", "ONLINE");
        } else {
            setProjectStatus("已停止", "OFFLINE");
        }
    }

    private boolean beginProjectOperation(ProjectOperation operation) {
        if (!activeOperation.compareAndSet(ProjectOperation.NONE, operation)) {
            appendLog("请等待当前操作完成: " + activeOperation.get().label);
            return false;
        }
        statusCheckVersion.incrementAndGet();
        applyBusyState(operation);
        return true;
    }

    private void finishProjectOperation(ProjectConfig project, int port) {
        Runnable finish = () -> {
            activeOperation.set(ProjectOperation.NONE);
            ProjectConfig selectedProject = getSelectedProject();
            if (selectedProject == null) {
                updateButtonStates(null);
            } else {
                updateButtonStates(selectedProject, runtimeState.resolveStatusPort(selectedProject));
            }
        };
        if (Platform.isFxApplicationThread()) {
            finish.run();
        } else {
            Platform.runLater(finish);
        }
    }

    private void applyBusyState(ProjectOperation operation) {
        projectComboBox.setDisable(true);
        portField.setDisable(true);
        profileField.setDisable(true);
        portNumField.setDisable(true);
        portQueryButton.setDisable(true);
        addProjectButton.setDisable(true);
        editProjectButton.setDisable(true);
        deleteProjectButton.setDisable(true);
        launchButton.setDisable(true);
        stopButton.setDisable(true);
        copyButton.setDisable(true);
        setProjectStatus(operation.label, "BUSY");
    }

    private boolean projectConfigurationAvailable() {
        ProjectOperation operation = activeOperation.get();
        if (operation == ProjectOperation.NONE) {
            return true;
        }
        appendLog("请等待当前操作完成: " + operation.label);
        return false;
    }

    private void setProjectStatus(String text, String state) {
        Runnable update = () -> {
            projectStatusLabel.setText(text);
            projectStatusLabel.getStyleClass().removeAll(
                    "status-offline", "status-busy", "status-online", "feedback-error");
            switch (state) {
                case "ONLINE" -> projectStatusLabel.getStyleClass().add("status-online");
                case "BUSY" -> projectStatusLabel.getStyleClass().add("status-busy");
                case "ERROR" -> projectStatusLabel.getStyleClass().addAll(
                        "status-offline", "feedback-error");
                default -> projectStatusLabel.getStyleClass().add("status-offline");
            }
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private enum ProjectOperation {
        NONE(""),
        COPYING("复制中"),
        LAUNCHING("启动中"),
        STOPPING("停止中"),
        QUERYING("查询端口中");

        private final String label;

        ProjectOperation(String label) {
            this.label = label;
        }
    }
}
