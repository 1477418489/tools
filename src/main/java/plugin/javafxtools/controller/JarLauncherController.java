package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import plugin.javafxtools.control.JarProjectStatusCell;
import plugin.javafxtools.control.LogViewer;
import plugin.javafxtools.model.JarProjectRuntimeStatus;
import plugin.javafxtools.model.JarProjectStatusSummary;
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
import plugin.javafxtools.service.JarProjectStatusModel;
import plugin.javafxtools.util.FxTheme;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JAR 启动器控制器，负责项目配置维护、文件复制、端口检查和 Java 进程启动停止。
 */
public class JarLauncherController {
    private static final DateTimeFormatter STATUS_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    private ListView<ProjectConfig> projectListView;

    @FXML
    private TextField portField;

    @FXML
    private TextField portNumField;

    @FXML
    private TextField profileField;

    private TextArea logArea;

    @FXML
    private LogViewer logViewer;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private Button launchButton;

    @FXML
    private Button stopButton;

    @FXML
    private Button copyButton;

    @FXML
    private Button openDirectoryButton;

    @FXML
    private Button openLogButton;

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

    @FXML
    private Label projectOverviewLabel;

    @FXML
    private Label statusRefreshTimeLabel;

    @FXML
    private Button refreshProjectStatusButton;

    private final JarLauncherRuntimeState runtimeState = new JarLauncherRuntimeState();

    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "JarLauncher-Worker");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService javaProcessExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("JarStartupMonitor-", 0).factory());

    private final AtomicLong statusCheckVersion = new AtomicLong();

    private final AtomicLong pathCheckVersion = new AtomicLong();

    private final JarProjectStatusModel projectStatusModel = new JarProjectStatusModel();

    private final Map<Integer, ProjectOperation> activeProjectOperations =
            new ConcurrentHashMap<>();

    private final AtomicBoolean portQueryInProgress = new AtomicBoolean();

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
            new JarLaunchService(javaProcessExecutor, javaProcessExecutor,
                    portProcessService, jarFileService,
                    this::appendLog, this::showError, this::confirmKillProcessOnPort,
                    this::finishProjectOperation, this::updateButtonStates,
                    this::recordRunningPort,
                    this::clearRunningPort);

    private final JarPortQueryService portQueryService =
            new JarPortQueryService(backgroundExecutor, portProcessService,
                    this::appendLog, this::showError, this::confirmKillProcessOnPort,
                    this::finishPortQuery);

    private JarLauncherUiSupport uiSupport;

    /**
     * 初始化 JAR 启动器页签。
     */
    @FXML
    public void initialize() {
        logArea = logViewer.getTextArea();
        uiSupport = new JarLauncherUiSupport(logArea);
        logViewer.setOnClear(this::handleClearLog);
        configureProjectList();
        applyProjectState(null, JarProjectRuntimeStatus.STOPPED);
        projectActionService = new JarProjectActionService(projectListView, portField, profileField,
                projectStore, projectDialogService, this::appendLog, this::showError,
                this::clearRunningPort,
                this::updateButtonStates, this::scheduleAllProjectStateChecks);
        projectActionService.initialize();
    }

    private void configureProjectList() {
        projectListView.setCellFactory(list -> new JarProjectStatusCell(
                projectStatusModel::statusOf, runtimeState::resolveStatusPort));
        projectListView.setPlaceholder(new Label("暂无项目"));
    }

    // 处理添加项目操作
    @FXML
    private void handleAddProject(ActionEvent event) {
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
        if (!beginPortQuery()) {
            return;
        }
        portQueryService.queryPort(port);
    }

    @FXML
    private void refreshProjectStatuses() {
        scheduleAllProjectStateChecks();
    }

    // 处理编辑项目操作
    @FXML
    private void handleEditProject(ActionEvent event) {
        if (!projectConfigurationAvailable(getSelectedProject())) {
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
        if (!beginProjectOperation(selectedProject, ProjectOperation.COPYING)) {
            return;
        }
        if (!copyActionService.copyProjectFiles(selectedProject)) {
            finishProjectOperation(selectedProject,
                    runtimeState.resolveStatusPort(selectedProject));
        }
    }

    @FXML
    private void handleOpenTargetDirectory() {
        openProjectPath(false);
    }

    @FXML
    private void handleOpenCurrentLog() {
        openProjectPath(true);
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
        if (!beginProjectOperation(selectedProject, ProjectOperation.LAUNCHING)) {
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
        if (!beginProjectOperation(selectedProject, ProjectOperation.STOPPING)) {
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
            applyProjectState(null, JarProjectRuntimeStatus.STOPPED);
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
            applyProjectState(null, JarProjectRuntimeStatus.STOPPED);
            return;
        }
        scheduleProjectStateCheck(project, port);
    }

    // 处理删除项目操作
    @FXML
    private void handleDeleteProject(ActionEvent event) {
        ProjectConfig project = getSelectedProject();
        if (project == null) {
            showError("请先选择要删除的项目");
            return;
        }
        if (!beginProjectOperation(project, ProjectOperation.DELETING)) {
            return;
        }
        int port = runtimeState.resolveStatusPort(project);
        try {
            backgroundExecutor.submit(() -> {
                JarProjectRuntimeStatus status = inspectProjectStatus(project, port);
                Platform.runLater(() -> finishDeleteCheck(project, status));
            });
        } catch (RejectedExecutionException e) {
            activeProjectOperations.remove(project.getId(), ProjectOperation.DELETING);
            applyProjectState(project, JarProjectRuntimeStatus.ERROR);
            showError("项目状态检查服务已关闭");
        }
    }

    private void finishDeleteCheck(ProjectConfig project, JarProjectRuntimeStatus status) {
        activeProjectOperations.remove(project.getId(), ProjectOperation.DELETING);
        ProjectConfig selected = getSelectedProject();
        if (selected == null || selected.getId() != project.getId()) {
            updateButtonStates(selected);
            return;
        }
        applyProjectState(project, status);
        projectActionService.deleteProject(status == JarProjectRuntimeStatus.RUNNING);
    }

    private JarProjectRuntimeStatus inspectProjectStatus(ProjectConfig project, int port) {
        if (!JarPortProcessService.isValidPort(port)) {
            return JarProjectRuntimeStatus.ERROR;
        }
        return switch (portProcessService.inspectProjectPort(project, port).state()) {
            case FREE -> JarProjectRuntimeStatus.STOPPED;
            case PROJECT_RUNNING -> JarProjectRuntimeStatus.RUNNING;
            case OCCUPIED -> JarProjectRuntimeStatus.CONFLICT;
        };
    }

    private void recordRunningPort(ProjectConfig project, int port) {
        runtimeState.recordRunningPort(project, port);
        refreshProjectListOnFxThread();
        ProjectConfig selected = getSelectedProject();
        if (selected != null && selected.getId() == project.getId()) {
            refreshProjectPathButtons(project);
        }
    }

    private void clearRunningPort(ProjectConfig project) {
        runtimeState.clearRunningPort(project);
        refreshProjectListOnFxThread();
    }

    private void refreshProjectListOnFxThread() {
        Runnable refresh = projectListView::refresh;
        if (Platform.isFxApplicationThread()) {
            refresh.run();
        } else {
            Platform.runLater(refresh);
        }
    }

    // 确认是否终止端口占用进程，只在 JavaFX 线程中弹出确认框。
    private boolean confirmKillProcessOnPort(int port) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        FxTheme.apply(alert);
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
        pathCheckVersion.incrementAndGet();
        projectStatusModel.invalidateBatch();
        activeProjectOperations.clear();
        portQueryInProgress.set(false);
        if (uiSupport != null) {
            uiSupport.shutdown();
        }
        javaProcessExecutor.shutdownNow();
        backgroundExecutor.shutdownNow();
        try {
            if (!javaProcessExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                appendLog("JAR启动监控任务未在超时时间内完全停止");
            }
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
        if (uiSupport != null) {
            uiSupport.clearLogs();
        } else if (logArea != null) {
            logArea.clear();
        }
    }

    private void scheduleAllProjectStateChecks() {
        List<ProjectConfig> projects = List.copyOf(projectListView.getItems());
        Set<Integer> protectedIds = Set.copyOf(activeProjectOperations.keySet());
        long version = projectStatusModel.beginBatch(projects, protectedIds);

        if (projects.isEmpty()) {
            updateProjectOverview();
            projectListView.refresh();
            return;
        }

        updateProjectOverview();
        projectListView.refresh();

        try {
            backgroundExecutor.submit(() -> {
                Map<Integer, JarProjectRuntimeStatus> checkedStatuses = new LinkedHashMap<>();
                for (ProjectConfig project : projects) {
                    if (!projectStatusModel.isBatchCurrent(version)
                            || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    if (protectedIds.contains(project.getId())) {
                        continue;
                    }
                    try {
                        int statusPort = runtimeState.resolveStatusPort(project);
                        JarProjectRuntimeStatus status = inspectProjectStatus(project, statusPort);
                        checkedStatuses.put(project.getId(), status);
                    } catch (RuntimeException e) {
                        checkedStatuses.put(project.getId(), JarProjectRuntimeStatus.ERROR);
                    }
                }
                Platform.runLater(() -> applyProjectStatusSnapshot(version, checkedStatuses));
            });
        } catch (RejectedExecutionException e) {
            projectStatusModel.failBatch(version, projects, protectedIds);
            updateProjectOverview();
            projectListView.refresh();
        }
    }

    private void applyProjectStatusSnapshot(long version,
                                             Map<Integer, JarProjectRuntimeStatus> checkedStatuses) {
        ProjectConfig selectedProject = getSelectedProject();
        ProjectOperation operation = operationOf(selectedProject);
        Set<Integer> protectedIds = Set.copyOf(activeProjectOperations.keySet());
        if (!projectStatusModel.applyBatch(version, checkedStatuses, protectedIds)) {
            return;
        }
        markStatusRefreshTime();
        updateProjectOverview();
        projectListView.refresh();

        if (selectedProject == null || operation != null) {
            return;
        }
        JarProjectRuntimeStatus status = projectStatusModel.statusOf(selectedProject);
        if (status != null && !status.isBusy()) {
            applyProjectState(selectedProject, status);
        }
    }

    private void setProjectRuntimeStatus(ProjectConfig project, JarProjectRuntimeStatus status) {
        if (project == null || project.getId() <= 0) {
            return;
        }
        projectStatusModel.setStatus(project, status);
        if (status.isTerminal()) {
            markStatusRefreshTime();
        }
        updateProjectOverview();
        projectListView.refresh();
    }

    private void updateProjectOverview() {
        int total = projectListView.getItems().size();
        JarProjectStatusSummary summary = projectStatusModel.summarize(total);
        if (total == 0) {
            statusRefreshTimeLabel.setText("尚未更新");
        }
        projectOverviewLabel.setText(summary.displayText());
        projectOverviewLabel.getStyleClass().removeAll(
                "status-offline", "status-busy", "status-online", "feedback-error");
        switch (summary.tone()) {
            case BUSY -> projectOverviewLabel.getStyleClass().add("status-busy");
            case ERROR -> projectOverviewLabel.getStyleClass().addAll(
                    "status-offline", "feedback-error");
            case ONLINE -> projectOverviewLabel.getStyleClass().add("status-online");
            case OFFLINE -> projectOverviewLabel.getStyleClass().add("status-offline");
        }
        refreshProjectStatusButton.setDisable(total == 0
                || projectStatusModel.hasCheckingProjects());
    }

    private void markStatusRefreshTime() {
        statusRefreshTimeLabel.setText("更新 " + LocalTime.now().format(STATUS_TIME_FORMAT));
    }

    private void scheduleProjectStateCheck(ProjectConfig project, int port) {
        ProjectOperation operation = operationOf(project);
        if (operation != null) {
            statusCheckVersion.incrementAndGet();
            applyBusyState(project, operation);
            return;
        }
        long version = statusCheckVersion.incrementAndGet();
        launchButton.setDisable(true);
        stopButton.setDisable(true);
        copyButton.setDisable(true);
        pathCheckVersion.incrementAndGet();
        openDirectoryButton.setDisable(true);
        openLogButton.setDisable(true);
        setProjectRuntimeStatus(project, JarProjectRuntimeStatus.CHECKING);
        setProjectStatus("检查中", "BUSY");

        try {
            backgroundExecutor.submit(() -> {
                try {
                    JarProjectRuntimeStatus status = inspectProjectStatus(project, port);
                    Platform.runLater(() -> {
                        ProjectConfig selected = getSelectedProject();
                        if (statusCheckVersion.get() != version
                                || selected == null
                                || selected.getId() != project.getId()) {
                            return;
                        }
                        applyProjectState(project, status);
                    });
                } catch (RuntimeException e) {
                    Platform.runLater(() -> {
                        if (statusCheckVersion.get() != version) {
                            return;
                        }
                        applyProjectState(project, JarProjectRuntimeStatus.ERROR);
                        showError("项目状态检查失败: " + e.getMessage());
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            applyProjectState(project, JarProjectRuntimeStatus.ERROR);
        }
    }

    private void applyProjectState(ProjectConfig project, JarProjectRuntimeStatus status) {
        ProjectOperation operation = operationOf(project);
        if (operation != null) {
            applyBusyState(project, operation);
            return;
        }
        boolean noProject = project == null;
        boolean running = status == JarProjectRuntimeStatus.RUNNING;
        boolean conflict = status == JarProjectRuntimeStatus.CONFLICT;
        projectListView.setDisable(false);
        portField.setDisable(noProject);
        profileField.setDisable(noProject);
        portNumField.setDisable(portQueryInProgress.get());
        portQueryButton.setDisable(portQueryInProgress.get());
        addProjectButton.setDisable(false);
        editProjectButton.setDisable(noProject);
        deleteProjectButton.setDisable(noProject);
        launchButton.setDisable(noProject || running || conflict);
        stopButton.setDisable(noProject || !running);
        copyButton.setDisable(noProject || running || progressBar.isVisible());
        refreshProjectPathButtons(project);
        refreshProjectStatusButton.setDisable(projectListView.getItems().isEmpty());
        if (noProject) {
            setProjectStatus("未选择项目", "OFFLINE");
        } else if (running) {
            setProjectRuntimeStatus(project, JarProjectRuntimeStatus.RUNNING);
            setProjectStatus("运行中", "ONLINE");
        } else if (conflict) {
            setProjectRuntimeStatus(project, JarProjectRuntimeStatus.CONFLICT);
            setProjectStatus("端口占用", "ERROR");
        } else if (status == JarProjectRuntimeStatus.ERROR) {
            setProjectRuntimeStatus(project, JarProjectRuntimeStatus.ERROR);
            setProjectStatus("检查失败", "ERROR");
        } else {
            setProjectRuntimeStatus(project, JarProjectRuntimeStatus.STOPPED);
            setProjectStatus("已停止", "OFFLINE");
        }
    }

    private boolean beginProjectOperation(ProjectConfig project, ProjectOperation operation) {
        if (project == null || project.getId() <= 0) {
            showError("项目配置无效");
            return false;
        }
        ProjectOperation existing = activeProjectOperations.putIfAbsent(project.getId(), operation);
        if (existing != null) {
            appendLog("[" + project.getName() + "] 请等待当前操作完成: " + existing.label);
            return false;
        }
        projectStatusModel.invalidateBatch();
        statusCheckVersion.incrementAndGet();
        applyBusyState(project, operation);
        return true;
    }

    private void finishProjectOperation(ProjectConfig project, int port) {
        Runnable finish = () -> {
            if (project != null) {
                activeProjectOperations.remove(project.getId());
            }
            ProjectConfig selectedProject = getSelectedProject();
            if (selectedProject == null) {
                updateButtonStates(null);
                if (project != null) {
                    refreshUnselectedProjectState(project, port);
                }
            } else if (project != null && selectedProject.getId() != project.getId()) {
                refreshUnselectedProjectState(project, port);
                ProjectOperation selectedOperation = operationOf(selectedProject);
                if (selectedOperation != null) {
                    applyBusyState(selectedProject, selectedOperation);
                } else {
                    JarProjectRuntimeStatus selectedStatus = projectStatusModel.statusOf(selectedProject);
                    if (selectedStatus == JarProjectRuntimeStatus.UNKNOWN
                            || selectedStatus.isBusy()) {
                        scheduleProjectStateCheck(selectedProject,
                                runtimeState.resolveStatusPort(selectedProject));
                    } else {
                        applyProjectState(selectedProject, selectedStatus);
                    }
                }
            } else {
                updateButtonStates(selectedProject, port > 0
                        ? port : runtimeState.resolveStatusPort(selectedProject));
            }
        };
        if (Platform.isFxApplicationThread()) {
            finish.run();
        } else {
            Platform.runLater(finish);
        }
    }

    private void refreshUnselectedProjectState(ProjectConfig project, int port) {
        try {
            backgroundExecutor.submit(() -> {
                JarProjectRuntimeStatus status;
                try {
                    status = inspectProjectStatus(project, port);
                } catch (RuntimeException e) {
                    status = JarProjectRuntimeStatus.ERROR;
                }
                JarProjectRuntimeStatus checkedStatus = status;
                Platform.runLater(() -> {
                    if (operationOf(project) == null) {
                        setProjectRuntimeStatus(project, checkedStatus);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            setProjectRuntimeStatus(project, JarProjectRuntimeStatus.ERROR);
        }
    }

    private void applyBusyState(ProjectConfig project, ProjectOperation operation) {
        projectListView.setDisable(false);
        portField.setDisable(true);
        profileField.setDisable(true);
        portNumField.setDisable(portQueryInProgress.get());
        portQueryButton.setDisable(portQueryInProgress.get());
        addProjectButton.setDisable(false);
        editProjectButton.setDisable(true);
        deleteProjectButton.setDisable(true);
        launchButton.setDisable(true);
        stopButton.setDisable(true);
        copyButton.setDisable(true);
        pathCheckVersion.incrementAndGet();
        openDirectoryButton.setDisable(true);
        openLogButton.setDisable(true);
        refreshProjectStatusButton.setDisable(projectListView.getItems().isEmpty());
        if (project != null && operation.runtimeStatus != null) {
            setProjectRuntimeStatus(project, operation.runtimeStatus);
        }
        refreshProjectPathButtons(project);
        setProjectStatus(operation.label, "BUSY");
    }

    private boolean projectConfigurationAvailable(ProjectConfig project) {
        ProjectOperation operation = operationOf(project);
        if (operation == null) {
            return true;
        }
        appendLog("[" + project.getName() + "] 请等待当前操作完成: " + operation.label);
        return false;
    }

    private ProjectOperation operationOf(ProjectConfig project) {
        return project == null ? null : activeProjectOperations.get(project.getId());
    }

    private boolean beginPortQuery() {
        if (!portQueryInProgress.compareAndSet(false, true)) {
            appendLog("端口查询正在进行中");
            return false;
        }
        portNumField.setDisable(true);
        portQueryButton.setDisable(true);
        return true;
    }

    private void finishPortQuery() {
        portQueryInProgress.set(false);
        portNumField.setDisable(false);
        portQueryButton.setDisable(false);
    }

    private void openProjectPath(boolean logFile) {
        ProjectConfig project = getSelectedProject();
        if (project == null) {
            showPathError("请先选择项目");
            return;
        }
        int port = runtimeState.resolveStatusPort(project);
        Button sourceButton = logFile ? openLogButton : openDirectoryButton;
        pathCheckVersion.incrementAndGet();
        sourceButton.setDisable(true);
        try {
            backgroundExecutor.submit(() -> {
                try {
                    Path path = logFile
                            ? jarFileService.resolveOutputLog(project, port)
                            : jarFileService.resolveTargetDirectory(project);
                    boolean available = logFile ? Files.isRegularFile(path) : Files.isDirectory(path);
                    if (!available) {
                        throw new IOException((logFile ? "当前端口日志不存在: " : "目标目录不存在: ")
                                + path);
                    }
                    if (!Desktop.isDesktopSupported()
                            || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        throw new IOException("当前系统不支持打开文件或目录");
                    }
                    Desktop.getDesktop().open(path.toFile());
                    appendLog((logFile ? "已打开运行日志: " : "已打开目标目录: ") + path);
                } catch (IOException | RuntimeException e) {
                    showPathError("打开失败: " + readableMessage(e));
                } finally {
                    Platform.runLater(() -> refreshProjectPathButtons(getSelectedProject()));
                }
            });
        } catch (RejectedExecutionException e) {
            refreshProjectPathButtons(project);
            showPathError("JAR 启动器后台服务已关闭");
        }
    }

    private void refreshProjectPathButtons(ProjectConfig project) {
        long version = pathCheckVersion.incrementAndGet();
        if (project == null) {
            openDirectoryButton.setDisable(true);
            openLogButton.setDisable(true);
            return;
        }
        openDirectoryButton.setDisable(true);
        openLogButton.setDisable(true);
        int projectId = project.getId();
        int port = runtimeState.resolveStatusPort(project);
        try {
            backgroundExecutor.submit(() -> {
                boolean directoryAvailable = false;
                boolean logAvailable = false;
                try {
                    directoryAvailable = Files.isDirectory(
                            jarFileService.resolveTargetDirectory(project));
                    logAvailable = Files.isRegularFile(
                            jarFileService.resolveOutputLog(project, port));
                } catch (IOException | RuntimeException ignored) {
                    // 无效或不可访问的路径保持禁用，不打断项目状态检查。
                }
                boolean finalDirectoryAvailable = directoryAvailable;
                boolean finalLogAvailable = logAvailable;
                Platform.runLater(() -> {
                    ProjectConfig selected = getSelectedProject();
                    if (pathCheckVersion.get() != version
                            || selected == null
                            || selected.getId() != projectId) {
                        return;
                    }
                    openDirectoryButton.setDisable(!finalDirectoryAvailable);
                    openLogButton.setDisable(!finalLogAvailable);
                });
            });
        } catch (RejectedExecutionException e) {
            openDirectoryButton.setDisable(true);
            openLogButton.setDisable(true);
        }
    }

    private void showPathError(String message) {
        appendLog(message);
        if (uiSupport != null) {
            uiSupport.showError(message);
        }
    }

    private String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
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
        COPYING("复制中", null),
        LAUNCHING("启动中", JarProjectRuntimeStatus.STARTING),
        STOPPING("停止中", JarProjectRuntimeStatus.STOPPING),
        DELETING("检查中", null);

        private final String label;
        private final JarProjectRuntimeStatus runtimeStatus;

        ProjectOperation(String label, JarProjectRuntimeStatus runtimeStatus) {
            this.label = label;
            this.runtimeStatus = runtimeStatus;
        }
    }
}
