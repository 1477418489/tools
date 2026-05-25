package plugin.javafxtools.controller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import plugin.javafxtools.model.ProjectConfig;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * JAR 启动器控制器，负责项目配置维护、文件复制、端口检查和 Java 进程启动停止。
 */
public class JarLauncherController {
    /**
     * 项目配置选择框。
     */
    @FXML
    private ComboBox<ProjectConfig> projectComboBox;

    /**
     * 启动端口输入框。
     */
    @FXML
    private TextField portField;

    /**
     * 端口占用查询输入框。
     */
    @FXML
    private TextField portNumField;

    /**
     * Spring profile 输入框。
     */
    @FXML
    private TextField profileField;

    /**
     * 操作日志输出区域。
     */
    @FXML
    private TextArea logArea;

    /**
     * 进程输出展示区域。
     */
    @FXML
    private TextArea processOutputArea;

    /**
     * 文件复制或启动过程进度条。
     */
    @FXML
    private ProgressBar progressBar;

    /**
     * 启动项目按钮。
     */
    @FXML
    private Button launchButton;

    /**
     * 停止项目按钮。
     */
    @FXML
    private Button stopButton;

    /**
     * 以项目 ID 为键保存的项目配置集合。
     */
    private final Map<Integer, ProjectConfig> projects = new HashMap<>();

    /**
     * 当前选中的项目配置。
     */
    private ProjectConfig selectedProject;

    /**
     * 当前运行中的端口，-1 表示无运行实例。
     */
    private volatile int runningPort = -1;

    /**
     * 项目配置持久化文件路径。
     */
    private static final String PROJECTS_CONFIG_FILE = "userData/jar_launcher_projects.json";

    /**
     * JAR 启动器日志最大保留行数。
     */
    private static final int MAX_LOG_LINES = 800;

    /**
     * 端口查询、文件复制、启动停止等耗时操作的共享后台线程池。
     */
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "JarLauncher-Worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * 初始化 JAR 启动器页签。
     */
    @FXML
    public void initialize() {

        // 从JSON文件加载项目配置
        loadProjectsFromJson();
        // 绑定下拉框
        projectComboBox.setItems(FXCollections.observableArrayList(projects.values()));
        projectComboBox.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldVal, newVal) -> {
                    if (newVal != null) {
                        selectedProject = newVal;
                        portField.setText(String.valueOf(newVal.getDefaultPort()));
                        profileField.setText(newVal.getDefaultProfile());
                        updateButtonStates(newVal);
                    }
                });
    }

    // 处理添加项目操作
    @FXML
    private void handleAddProject(ActionEvent event) {
        // 创建项目配置对话框
        ProjectConfig newProject = showProjectConfigDialog(new ProjectConfig());
        if (newProject != null) {
            // 设置项目ID（使用当前最大ID+1）
            int maxId = projects.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            newProject.setId(maxId + 1);

            // 添加到项目列表
            projects.put(newProject.getId(), newProject);

            // 更新ComboBox
            projectComboBox.setItems(FXCollections.observableArrayList(projects.values()));

            // 保存到JSON文件
            saveProjectsToJson();

            appendLog("已添加新项目: " + newProject.getName());
        }
    }

    // 查询端口占用
    @FXML
    private void queryPort() {
        String port = portNumField.getText().trim();
        if (port.isEmpty()) {
            showError("端口为空,请输入端口");
            return;
        }
        int portNum;
        try {
            portNum = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            showError("端口必须是数字");
            return;
        }
        final int targetPort = portNum;
        backgroundExecutor.submit(() -> {
            boolean inUse = checkPortInUse(targetPort);
            if (inUse) {
                String processInfo = getProcessUsingPort(targetPort);
                Platform.runLater(() -> {
                    appendLog("端口:" + targetPort + " 被占用，" + processInfo);
                    if (confirmKillProcessOnPort(targetPort)) {
                        backgroundExecutor.submit(() -> killProcessOnPort(targetPort));
                    }
                });
            } else {
                Platform.runLater(() -> appendLog("端口:" + targetPort + " 未被占用"));
            }
        });
    }

    // 处理编辑项目操作
    @FXML
    private void handleEditProject(ActionEvent event) {
        if (selectedProject == null) {
            showError("请先选择要编辑的项目");
            return;
        }

        // 创建项目配置对话框，传入选中的项目作为初始值
        ProjectConfig editedProject = showProjectConfigDialog(new ProjectConfig(selectedProject));
        if (editedProject != null) {
            // 保持项目ID不变
            editedProject.setId(selectedProject.getId());

            // 更新项目列表
            projects.put(editedProject.getId(), editedProject);

            // 更新ComboBox
            projectComboBox.setItems(FXCollections.observableArrayList(projects.values()));
            projectComboBox.getSelectionModel().select(editedProject);

            // 保存到JSON文件
            saveProjectsToJson();

            appendLog("已更新项目: " + editedProject.getName());
        }
    }

    // 显示项目配置对话框
    private ProjectConfig showProjectConfigDialog(ProjectConfig project) {
        // 创建对话框
        Dialog<ProjectConfig> dialog = new Dialog<>();
        dialog.setTitle("项目配置");
        dialog.setHeaderText("配置项目参数");

        // 设置按钮
        ButtonType saveButtonType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        // 创建表单内容
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(20, 150, 10, 10));

        TextField nameField = new TextField(project.getName());
        nameField.setPrefColumnCount(30);
        TextField sourceJarField = new TextField(project.getSourceJar());
        TextField targetJarField = new TextField(project.getTargetJar());
        TextField sourceLibField = new TextField(project.getSourceLib());
        TextField libTargetField = new TextField(project.getLibTarget());
        TextField defaultPortField = new TextField(String.valueOf(project.getDefaultPort()));
        TextField defaultProfileField = new TextField(project.getDefaultProfile());
        TextField jvmOptsField = new TextField(project.getJvmOpts());
        TextField basePathField = new TextField(project.getOtherOpts());

        grid.add(new Label("项目名称:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("源JAR路径:"), 0, 1);
        grid.add(sourceJarField, 1, 1);
        grid.add(new Label("目标JAR路径:"), 0, 2);
        grid.add(targetJarField, 1, 2);
        grid.add(new Label("源Lib路径:"), 0, 3);
        grid.add(sourceLibField, 1, 3);
        grid.add(new Label("目标Lib路径:"), 0, 4);
        grid.add(libTargetField, 1, 4);
        grid.add(new Label("默认端口:"), 0, 5);
        grid.add(defaultPortField, 1, 5);
        grid.add(new Label("默认环境:"), 0, 6);
        grid.add(defaultProfileField, 1, 6);
        grid.add(new Label("JVM参数:"), 0, 7);
        grid.add(jvmOptsField, 1, 7);
        grid.add(new Label("其它项目参数:"), 0, 8);
        grid.add(basePathField, 1, 8);

        dialog.getDialogPane().setContent(grid);

        // 请求焦点到第一个输入框
        Platform.runLater(() -> nameField.requestFocus());

        // 转换结果
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                try {
                    ProjectConfig newProject = new ProjectConfig();
                    newProject.setName(nameField.getText());
                    newProject.setSourceJar(sourceJarField.getText());
                    newProject.setTargetJar(targetJarField.getText());
                    newProject.setSourceLib(sourceLibField.getText());
                    newProject.setLibTarget(libTargetField.getText());
                    newProject.setDefaultPort(Integer.parseInt(defaultPortField.getText()));
                    newProject.setDefaultProfile(defaultProfileField.getText());
                    newProject.setJvmOpts(jvmOptsField.getText());
                    newProject.setOtherOpts(basePathField.getText());
                    return newProject;
                } catch (NumberFormatException e) {
                    showError("端口必须是数字");
                    return null;
                }
            }
            return null;
        });

        // 显示对话框并等待结果
        Optional<ProjectConfig> result = dialog.showAndWait();
        return result.orElse(null);
    }

    // 从JSON文件加载项目配置
    private void loadProjectsFromJson() {
        File configFile = new File(PROJECTS_CONFIG_FILE);
        if (configFile.exists()) {
            try (Reader reader = new FileReader(configFile)) {
                Gson gson = new Gson();
                List<ProjectConfig> projectList = gson.fromJson(
                        reader,
                        new TypeToken<List<ProjectConfig>>() {
                        }.getType()
                );

                // 清空现有项目
                projects.clear();

                // 将列表转换为Map
                if (projectList != null) {
                    for (ProjectConfig project : projectList) {
                        projects.put(project.getId(), project);
                    }
                }

                appendLog("已从JSON文件加载 " + projects.size() + " 个项目配置");
            } catch (Exception e) {
                appendLog("读取项目配置文件失败: " + e.getMessage());
                // 如果读取失败，使用默认配置
                initDefaultProjects();
            }
        } else {
            // 如果配置文件不存在，使用默认配置并保存
            initDefaultProjects();
            saveProjectsToJson();
        }
    }

    // 保存项目配置到JSON文件
    private void saveProjectsToJson() {
        try (Writer writer = new FileWriter(PROJECTS_CONFIG_FILE)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            List<ProjectConfig> projectList = new ArrayList<>(projects.values());
            gson.toJson(projectList, writer);
            appendLog("项目配置已保存到: " + PROJECTS_CONFIG_FILE);
        } catch (Exception e) {
            appendLog("保存项目配置失败: " + e.getMessage());
        }
    }

    // 初始化默认项目配置（示例数据）
    private void initDefaultProjects() {
        projects.clear();

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

        appendLog("已初始化默认项目配置");
    }

    // 处理复制文件操作
    @FXML
    private void handleCopyAction(ActionEvent event) {
        if (selectedProject == null) {
            showError("请先选择项目");
            return;
        }
        // 捕获当前选中项目，避免后台线程中 selectedProject 被切换
        ProjectConfig projectSnapshot = selectedProject;

        Task<Void> copyTask = new Task<>() {
            /**
             * 后台执行文件复制，避免阻塞 JavaFX 线程。
             *
             * @return 无返回值
             * @throws Exception 文件复制异常
             */
            @Override
            protected Void call() throws Exception {
                Platform.runLater(() -> {
                    progressBar.setVisible(true);
                    logArea.clear();
                });
                appendLog("开始执行文件操作...");

                try {
                    copyJarFile(projectSnapshot);

                    if (Files.exists(Paths.get(projectSnapshot.getSourceLib()))) {
                        copyLibDirectory(projectSnapshot);
                    }

                    Platform.runLater(() -> {
                        progressBar.setVisible(false);
                        appendLog("文件操作完成");
                        updateButtonStates(projectSnapshot, runningPort > 0 ? runningPort : projectSnapshot.getDefaultPort());
                    });
                } catch (IOException e) {
                    Platform.runLater(() -> {
                        progressBar.setVisible(false);
                        showError("文件操作失败: " + e.getMessage());
                    });
                }

                return null;
            }
        };

        backgroundExecutor.submit(copyTask);
    }

    // 处理启动操作
    @FXML
    private void handleLaunchAction(ActionEvent event) {
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
        launchButton.setDisable(true);

        Task<Void> launchTask = new Task<>() {
            /**
             * 后台检查端口占用并触发启动流程。
             *
             * @return 无返回值
             * @throws Exception 启动前检查异常
             */
            @Override
            protected Void call() throws Exception {
                // 后台检查端口占用
                if (checkPortInUse(launchPort)) {
                    Platform.runLater(() -> {
                        if (!confirmKillProcessOnPort(launchPort)) {
                            launchButton.setDisable(false);
                            return;
                        }
                        // 用户确认杀进程后，后台终止进程并继续启动。
                        backgroundExecutor.submit(() -> {
                            killProcessOnPort(launchPort);
                            startLaunchProcess(launchProject, launchPort, launchProfile);
                        });
                    });
                    return null;
                }
                startLaunchProcess(launchProject, launchPort, launchProfile);
                return null;
            }
        };
        launchTask.setOnFailed(e -> {
            Throwable ex = launchTask.getException();
            Platform.runLater(() -> {
                launchButton.setDisable(false);
                showError("启动失败: " + (ex != null ? ex.getMessage() : "未知错误"));
            });
        });

        backgroundExecutor.submit(launchTask);
    }

    /**
     * 创建后台任务启动指定项目。
     *
     * @param launchProject 要启动的项目配置
     * @param launchPort 启动端口
     * @param launchProfile 启动 profile
     */
    private void startLaunchProcess(ProjectConfig launchProject, int launchPort, String launchProfile) {
        Task<Void> innerTask = new Task<>() {
            /**
             * 后台启动 Java 进程并等待端口就绪。
             *
             * @return 无返回值
             * @throws Exception 启动或端口等待异常
             */
            @Override
            protected Void call() throws Exception {
                Process process = startJavaApplication(launchProject, launchPort, launchProfile);

                Platform.runLater(() -> {
                    appendLog("应用程序已作为独立进程启动");
                    appendLog("进程ID: " + process.pid());
                    appendLog("即使关闭此工具，应用程序也将继续运行");
                    runningPort = launchPort;
                    // 立即设置按钮为运行中状态
                    launchButton.setDisable(true);
                    stopButton.setDisable(false);
                });

                // 轮询等待端口就绪（最多等30秒）
                boolean portReady = false;
                for (int i = 0; i < 60; i++) {
                    Thread.sleep(500);
                    if (checkPortInUse(launchPort)) {
                        portReady = true;
                        break;
                    }
                }

                final boolean ready = portReady;
                Platform.runLater(() -> {
                    if (ready) {
                        appendLog("端口 " + launchPort + " 已就绪");
                    } else {
                        appendLog("等待端口就绪超时，应用可能仍在启动中");
                    }
                    updateButtonStates(launchProject, launchPort);
                });
                return null;
            }
        };
        innerTask.setOnFailed(e -> {
            Throwable ex = innerTask.getException();
            Platform.runLater(() -> {
                launchButton.setDisable(false);
                showError("启动失败: " + (ex != null ? ex.getMessage() : "未知错误"));
            });
        });

        backgroundExecutor.submit(innerTask);
    }

    // 处理停止操作
    @FXML
    private void handleStopAction(ActionEvent event) {
        if (selectedProject == null) {
            showError("请先选择项目");
            return;
        }
        // 停止时优先使用已记录的运行端口
        int port;
        if (runningPort > 0) {
            port = runningPort;
        } else {
            String portText = portField.getText();
            if (portText != null && !portText.trim().isEmpty()) {
                try {
                    port = Integer.parseInt(portText.trim());
                } catch (NumberFormatException e) {
                    port = selectedProject.getDefaultPort();
                }
            } else {
                port = selectedProject.getDefaultPort();
            }
        }
        final ProjectConfig stopProject = selectedProject;
        final int stopPort = port;
        stopButton.setDisable(true);

        backgroundExecutor.submit(() -> {
            if (checkPortInUse(stopPort)) {
                // 端口被占用，弹框确认（需切回FX线程）
                Platform.runLater(() -> {
                    if (confirmKillProcessOnPort(stopPort)) {
                        // 用户确认杀进程后，在后台终止进程并等待端口释放。
                        backgroundExecutor.submit(() -> {
                            killProcessOnPort(stopPort);
                            for (int i = 0; i < 10; i++) {
                                try {
                                    Thread.sleep(300);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                                if (!checkPortInUse(stopPort)) break;
                            }
                            if (!checkPortInUse(stopPort)) {
                                runningPort = -1;
                            }
                            Platform.runLater(() -> updateButtonStates(stopProject, stopPort));
                        });
                    } else {
                        // 用户取消，恢复按钮状态
                        updateButtonStates(stopProject, stopPort);
                    }
                });
            } else {
                // 端口未被占用，直接清除状态
                runningPort = -1;
                Platform.runLater(() -> updateButtonStates(stopProject, stopPort));
            }
        });
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
        updateButtonStates(project, project.getDefaultPort());
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
        if (selectedProject == null) {
            showError("请先选择要删除的项目");
            return;
        }

        // 检查项目是否正在运行
        if (isProjectRunning(selectedProject, runningPort > 0 ? runningPort : selectedProject.getDefaultPort())) {
            Alert runningAlert = new Alert(Alert.AlertType.WARNING);
            runningAlert.setTitle("项目正在运行");
            runningAlert.setHeaderText("项目 \"" + selectedProject.getName() + "\" 正在运行");
            runningAlert.setContentText("建议先停止项目再删除，否则可能导致数据丢失。\n\n是否仍要继续删除？");

            Optional<ButtonType> result = runningAlert.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                appendLog("用户取消了删除操作: " + selectedProject.getName());
                return;
            }
        }

        // 确认删除操作
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("确认删除");
        confirmAlert.setHeaderText("确定要删除项目 \"" + selectedProject.getName() + "\" 吗？");
        confirmAlert.setContentText("此操作不可撤销，项目配置将被永久删除。");

        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String projectName = selectedProject.getName();

            // 从项目列表中移除
            projects.remove(selectedProject.getId());

            // 更新ComboBox
            projectComboBox.setItems(FXCollections.observableArrayList(projects.values()));

            // 清空选择
            projectComboBox.getSelectionModel().clearSelection();
            selectedProject = null;
            runningPort = -1;

            // 清空端口和环境字段
            portField.clear();
            profileField.clear();

            // 保存到JSON文件
            saveProjectsToJson();

            appendLog("已删除项目: " + projectName);
        }
    }

    // 检查项目是否正在运行（使用默认端口）
    private boolean isProjectRunning(ProjectConfig project) {
        return isProjectRunning(project, project.getDefaultPort());
    }

    // 检查项目是否在指定端口上运行（仅依赖端口检测，最可靠）
    private boolean isProjectRunning(ProjectConfig project, int port) {
        return checkPortInUse(port);
    }

    // 端口检查实现
    private boolean checkPortInUse(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // 获取占用端口的进程信息
    private String getProcessUsingPort(int port) {
        try {
            // 使用 netstat 获取端口对应的 PID
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "netstat -ano");
            Process process = pb.start();
            String pid = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                String portSuffix = ":" + port + " ";
                while ((line = reader.readLine()) != null) {
                    if (line.contains(portSuffix) && line.contains("LISTENING")) {
                        String[] parts = line.trim().split("\\s+");
                        pid = parts[parts.length - 1];
                        break;
                    }
                }
            }
            if (pid != null) {
                ProcessBuilder namePb = new ProcessBuilder("cmd.exe", "/c",
                        "wmic process where processid=" + pid + " get name,commandline /FORMAT:LIST");
                Process nameProcess = namePb.start();
                StringBuilder processInfo = new StringBuilder();
                try (BufferedReader nameReader = new BufferedReader(
                        new InputStreamReader(nameProcess.getInputStream(), "GBK"))) {
                    String nameLine;
                    while ((nameLine = nameReader.readLine()) != null) {
                        String trimmed = nameLine.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith("CommandLine")
                                && !trimmed.startsWith("Name")) {
                            processInfo.append(trimmed).append(" ");
                        }
                    }
                }
                return processInfo.length() > 0 ? "PID:" + pid + " " + processInfo : "PID:" + pid;
            }
        } catch (Exception e) {
            appendLog("获取进程信息时出错: " + e.getMessage());
        }
        return "未知进程";
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

    // 终止占用进程
    private void killProcessOnPort(int port) {
        try {
            ProcessBuilder getPidPb = new ProcessBuilder("cmd.exe", "/c", "netstat -ano");
            Process getPidProcess = getPidPb.start();
            String pid = null;
            String portSuffix = ":" + port + " ";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getPidProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(portSuffix) && line.contains("LISTENING")) {
                        String[] parts = line.trim().split("\\s+");
                        pid = parts[parts.length - 1];
                        break;
                    }
                }
            }
            if (pid != null) {
                ProcessBuilder killPb = new ProcessBuilder(
                        "cmd.exe", "/c", "taskkill /PID " + pid + " /F");
                Process killProcess = killPb.start();
                boolean finished = killProcess.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    killProcess.destroyForcibly();
                    appendLog("终止进程超时 (PID: " + pid + ")");
                    return;
                }
                int result = killProcess.exitValue();
                if (result == 0) {
                    appendLog("成功终止占用端口 " + port + " 的进程 (PID: " + pid + ")");
                } else {
                    appendLog("终止进程失败 (PID: " + pid + ")");
                }
            } else {
                appendLog("未找到占用端口 " + port + " 的 LISTENING 进程");
            }
        } catch (Exception e) {
            showError("终止进程失败: " + e.getMessage());
        }
    }

    // 文件复制实现
    private void copyJarFile(ProjectConfig project) throws IOException {
        Path source = Paths.get(project.getSourceJar());
        Path target = Paths.get(project.getTargetJar());

        if (!Files.exists(target.getParent())) {
            Files.createDirectories(target.getParent());
        }

        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        appendLog("已复制JAR文件到: " + target);
    }

    // 递归复制lib目录
    private void copyLibDirectory(ProjectConfig project) throws IOException {
        Path source = Paths.get(project.getSourceLib());
        Path target = Paths.get(project.getLibTarget());

        if (Files.exists(target)) {
            // 清空目标目录
            try (Stream<Path> walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) { /* Ignore */ }
                        });
            }
        }

        if (Files.exists(source)) {
            try (Stream<Path> walk = Files.walk(source)) {
                walk.forEach(sourcePath -> {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    try {
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else {
                            Files.copy(sourcePath, targetPath);
                        }
                    } catch (IOException e) {
                        appendLog("复制失败: " + sourcePath + " -> " + targetPath);
                    }
                });
            }
            appendLog("已复制lib目录到: " + target);
        }
    }

    // 启动Java应用
    private Process startJavaApplication(ProjectConfig project, int port, String profile) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("cmd.exe");
        command.add("/c");

        StringBuilder fullCommand = new StringBuilder();
        fullCommand.append("chcp 65001 >nul && ");
        fullCommand.append("java");
        fullCommand.append(" -Dfile.encoding=UTF-8");
        fullCommand.append(" -Dsun.stdout.encoding=UTF-8");
        fullCommand.append(" -Dsun.stderr.encoding=UTF-8");

        if (project.getJvmOpts() != null && !project.getJvmOpts().isEmpty()) {
            fullCommand.append(" ").append(project.getJvmOpts());
        }

        fullCommand.append(" -jar \"").append(project.getTargetJar()).append("\"");
        fullCommand.append(" --server.port=").append(port);
        fullCommand.append(" --spring.profiles.active=").append(profile);
        String otherOpts = project.getOtherOpts();
        if (otherOpts != null && !otherOpts.trim().isEmpty()) {
            fullCommand.append(" ").append(otherOpts.trim());
        }

        command.add("start");
        command.add("\"JAR_" + port + "\"");
        command.add("cmd.exe");
        command.add("/c");
        command.add(fullCommand.toString());

        ProcessBuilder pb = new ProcessBuilder(command);

        // 设置工作目录
        Path jarPath = Paths.get(project.getTargetJar());
        if (jarPath.getParent() != null) {
            pb.directory(jarPath.getParent().toFile());
        }

        // 重定向输入输出流以确保独立运行
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        // 设置环境变量确保独立性
        Map<String, String> env = pb.environment();
        env.put("JAVA_TOOL_OPTIONS", ""); // 清除可能影响独立性的工具选项

        // 启动进程
        Process process = pb.start();

        try {
            Thread.sleep(100);
            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();
        } catch (Exception e) {
            // 忽略关闭流时的异常
        }
        appendLog("已启动独立进程，PID: " + process.pid() + "，启动指令: " + pb.command());
        return process;
    }

    // 日志输出辅助方法（线程安全）
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 线程安全地追加 JAR 启动器日志。
     *
     * @param message 日志内容
     */
    private void appendLog(String message) {
        String timestamp = LocalTime.now().format(LOG_TIME_FMT);
        String line = "[" + timestamp + "] " + message + "\n";
        if (Platform.isFxApplicationThread()) {
            appendLogOnFxThread(line);
        } else {
            Platform.runLater(() -> appendLogOnFxThread(line));
        }
    }

    /**
     * 在 JavaFX 线程中追加日志并裁剪旧内容。
     *
     * @param line 已格式化的日志行
     */
    private void appendLogOnFxThread(String line) {
        if (logArea == null) {
            return;
        }
        trimLogs();
        logArea.appendText(line);
        logArea.setScrollTop(Double.MAX_VALUE);
    }

    /**
     * 裁剪旧日志，避免 TextArea 长时间运行后占用过多内存。
     */
    private void trimLogs() {
        int lineCount = logArea.getParagraphs().size();
        if (lineCount < MAX_LOG_LINES) {
            return;
        }

        String text = logArea.getText();
        int linesToRemove = Math.max(100, lineCount - MAX_LOG_LINES + 1);
        int deleteIndex = 0;
        while (linesToRemove > 0) {
            int nextNewline = text.indexOf('\n', deleteIndex);
            if (nextNewline < 0) {
                break;
            }
            deleteIndex = nextNewline + 1;
            linesToRemove--;
        }
        if (deleteIndex > 0) {
            logArea.deleteText(0, deleteIndex);
        }
    }

    // 错误提示
    private void showError(String message) {
        Runnable showAlert = () -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("错误");
            alert.setContentText(message);
            alert.showAndWait();
        };
        if (Platform.isFxApplicationThread()) {
            showAlert.run();
        } else {
            Platform.runLater(showAlert);
        }
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
