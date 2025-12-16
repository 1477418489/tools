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
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;

public class JarLauncherController {
    // FXML注入组件
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
    private TextArea processOutputArea;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Button launchButton;
    @FXML
    private Button stopButton;

    // 项目配置集合
    private final Map<Integer, ProjectConfig> projects = new HashMap<>();
    private ProjectConfig selectedProject;
    private Process currentProcess;

    // 配置文件路径
    private static final String PROJECTS_CONFIG_FILE = "userData/jar_launcher_projects.json";

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

    // 处理添加项目操作
    @FXML
    private void queryPort() {
        String port = portNumField.getText().trim();
        if (port.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("端口为空");
            alert.setHeaderText("端口为空,请输入端口");
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isEmpty() || result.get() == ButtonType.OK) {
                return;
            }
        }
        // 检查端口占用
        int portNum = Integer.parseInt(port);
        String processInfo = getProcessUsingPort(portNum);
        appendLog("端口:"+ processInfo + " 占用");
        if (checkPortInUse(portNum)) {
            if (!handlePortConflict(portNum)) return;
        }else{
            appendLog("端口:"+ port + " 未被占用");
        }
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
    private void handleCopyAction(ActionEvent event) { // 修改为JavaFX的ActionEvent
        if (selectedProject == null) {
            showError("请先选择项目");
            return;
        }

        Task<Void> copyTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Platform.runLater(() -> {
                    progressBar.setVisible(true);
                    logArea.clear();
                    appendLog("开始执行文件操作...");
                });

                try {
                    // 复制JAR文件
                    copyJarFile(selectedProject);

                    // 复制lib目录（如果存在）
                    if (Files.exists(Paths.get(selectedProject.getSourceLib()))) {
                        copyLibDirectory(selectedProject);
                    }

                    Platform.runLater(() -> {
                        progressBar.setVisible(false);
                        appendLog("文件操作完成");
                        launchButton.setDisable(false);
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

        new Thread(copyTask).start();
    }

    // 处理启动操作
    @FXML
    private void handleLaunchAction(ActionEvent event) {
        if (selectedProject == null) {
            showError("请先选择项目");
            return;
        }

        // 获取用户输入
        int port = portField.getText().isEmpty() ?
                selectedProject.getDefaultPort() : Integer.parseInt(portField.getText());
        String profile = profileField.getText().isEmpty() ?
                selectedProject.getDefaultProfile() : profileField.getText();

        // 检查端口占用
        if (checkPortInUse(port)) {
            if (!handlePortConflict(port)) return;
        }

        // 禁用启动按钮
        launchButton.setDisable(false);
        stopButton.setDisable(false);

        // 启动应用
        Task<Void> launchTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // 启动独立进程
                Process process = startJavaApplication(selectedProject, port, profile);

                // 不再监控进程输出，因为进程是完全独立的
                Platform.runLater(() -> {
                    appendLog("应用程序已作为独立进程启动");
                    appendLog("进程ID: " + (process != null ? process.pid() : "unknown"));
                    appendLog("即使关闭此工具，应用程序也将继续运行");

                    // 启用停止按钮（虽然实际上无法通过工具停止独立进程）
                    stopButton.setDisable(false);
                });
                return null;
            }
        };

        new Thread(launchTask).start();
    }

    // 处理停止操作
    @FXML
    private void handleStopAction(ActionEvent event) {
        if (selectedProject == null) {
            showError("请先选择项目");
            return;
        }
        // 获取用户输入
        int port = portField.getText().isEmpty() ?
                selectedProject.getDefaultPort() : Integer.parseInt(portField.getText());
        // 检查端口占用
        if (checkPortInUse(port)) {
            if (!handlePortConflict(port)) return;
        }
        // 重置按钮状态
        launchButton.setDisable(false);
        stopButton.setDisable(true);
    }

    // 处理删除项目操作
    @FXML
    private void handleDeleteProject(ActionEvent event) {
        if (selectedProject == null) {
            showError("请先选择要删除的项目");
            return;
        }

        // 检查项目是否正在运行
        if (isProjectRunning(selectedProject)) {
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

            // 清空端口和环境字段
            portField.clear();
            profileField.clear();

            // 保存到JSON文件
            saveProjectsToJson();

            appendLog("已删除项目: " + projectName);
        }
    }

    // 检查特定项目的进程是否正在运行
    private boolean isProjectProcessRunning(ProjectConfig project) {
        try {
            // 获取目标JAR文件名
            String jarFileName = Paths.get(project.getTargetJar()).getFileName().toString();
            String processName = jarFileName.replace(".jar", "");

            // 根据操作系统选择不同的检查方式
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // Windows: 使用 tasklist 命令
                pb = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq java.exe", "/FO", "CSV", "/NH");
            } else {
                // Unix/Linux/macOS: 使用 ps 命令
                pb = new ProcessBuilder("ps", "aux");
            }

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                // 检查进程命令行是否包含目标JAR文件名
                if (line.contains(jarFileName) || line.contains(project.getTargetJar())) {
                    // 进一步检查是否包含项目特定的端口
                    if (line.contains("--server.port=" + project.getDefaultPort())) {
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            appendLog("检查进程状态时出错: " + e.getMessage());
            return false;
        }
    }

    // 检查项目是否正在运行
    private boolean isProjectRunning(ProjectConfig project) {
        // 首先检查默认端口是否被占用
        if (checkPortInUse(project.getDefaultPort())) {
            return true;
        }
        // 然后检查是否有对应的Java进程在运行
        return isProjectProcessRunning(project);
    }

    // 端口检查实现
    private boolean checkPortInUse(int port) {
        try (SocketChannel socketChannel = SocketChannel.open()) {
            return socketChannel.connect(new InetSocketAddress("localhost", port));
        } catch (IOException e) {
            return false;
        }
    }

    // 新增方法：获取占用端口的进程信息
    private String getProcessUsingPort(int port) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                // Windows系统使用netstat命令
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c",
                        "netstat -ano | findstr :" + port);
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line;
                String targetLine = null;
                // 查找匹配的端口行
                while ((line = reader.readLine()) != null) {
                    if (line.contains(":" + port) && (line.contains("LISTENING") || line.contains("ESTABLISHED"))) {
                        targetLine = line;
                        break;
                    }
                }
                if (targetLine != null) {
                    // 提取PID
                    String[] parts = targetLine.trim().split("\\s+");
                    if (parts.length >= 5) {
                        String pid = parts[parts.length - 1];
                        // 根据PID获取进程信息
                        ProcessBuilder namePb = new ProcessBuilder("cmd.exe", "/c",
                                "wmic process where processid=" + pid + " get name,processid,commandline");
                        Process nameProcess = namePb.start();
                        BufferedReader nameReader = new BufferedReader(
                                new InputStreamReader(nameProcess.getInputStream(), "GBK"));
                        StringBuilder processInfo = new StringBuilder();
                        String nameLine;
                        while ((nameLine = nameReader.readLine()) != null) {
                            if (!nameLine.trim().isEmpty() && !nameLine.contains("CommandLine")) {
                                processInfo.append(nameLine.trim()).append("\n");
                            }
                        }
                        if (!processInfo.isEmpty()) {
                            return processInfo.toString();
                        } else {
                            return "PID: " + pid;
                        }
                    }
                }
            }
        } catch (Exception e) {
            appendLog("获取进程信息时出错: " + e.getMessage());
        }
        return "未知进程";
    }

    // 处理端口冲突
    private boolean handlePortConflict(int port) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("端口冲突");
        alert.setHeaderText("检测到端口 " + port + " 被占用");
        alert.setContentText("是否终止占用进程？");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            killProcessOnPort(port);
            return true;
        }
        return false;
    }

    // 终止占用进程（Windows）
    private void killProcessOnPort(int port) {
        try {
            // 先获取占用端口的进程PID
            ProcessBuilder getPidPb = new ProcessBuilder(
                    "cmd.exe", "/c",
                    "for /f \"tokens=5\" %i in ('netstat -ano ^| findstr :" + port + "') do @echo %i"
            );
            Process getPidProcess = getPidPb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(getPidProcess.getInputStream()));
            String pidLine = reader.readLine();

            if (pidLine != null && !pidLine.trim().isEmpty()) {
                // 终止进程
                ProcessBuilder killPb = new ProcessBuilder(
                        "cmd.exe", "/c",
                        "taskkill /PID " + pidLine.trim() + " /F"
                );
                Process killProcess = killPb.start();
                int result = killProcess.waitFor();

                if (result == 0) {
                    appendLog("成功终止占用端口 " + port + " 的进程 (PID: " + pidLine.trim() + ")");
                } else {
                    appendLog("终止进程失败 (PID: " + pidLine.trim() + ")");
                }
            } else {
                appendLog("未找到占用端口 " + port + " 的进程");
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
            Files.walk(target)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) { /* Ignore */ }
                    });
        }

        if (Files.exists(source)) {
            Files.walk(source)
                    .forEach(sourcePath -> {
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
            appendLog("已复制lib目录到: " + target);
        }
    }

    // 启动Java应用
    private Process startJavaApplication(ProjectConfig project, int port, String profile) throws IOException {
        // 根据操作系统选择不同的启动方式
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;

        if (os.contains("win")) {
            // Windows: 使用 cmd /c start 命令确保完全独立运行
            List<String> command = new ArrayList<>();
            command.add("cmd.exe");
            command.add("/c");
            // 使用单条命令行，正确设置代码页并启动Java应用
            StringBuilder fullCommand = new StringBuilder();
            fullCommand.append("chcp 65001 >nul && "); // 设置UTF-8代码页

            // 构建实际的Java命令
            fullCommand.append("java");

            // 添加字符编码设置到JVM选项开头
            fullCommand.append(" -Dfile.encoding=UTF-8");
            fullCommand.append(" -Dsun.stdout.encoding=UTF-8");
            fullCommand.append(" -Dsun.stderr.encoding=UTF-8");

            // 添加用户定义的JVM选项
            if (project.getJvmOpts() != null && !project.getJvmOpts().isEmpty()) {
                fullCommand.append(" ").append(project.getJvmOpts());
            }

            fullCommand.append(" -jar \"").append(project.getTargetJar()).append("\"");
            fullCommand.append(" --server.port=").append(port);
            fullCommand.append(" --spring.profiles.active=").append(profile);
            fullCommand.append(" ").append(project.getOtherOpts()).append("\"");

            // 使用start命令启动独立进程
            command.add("start");
            command.add("\"JAR_" + port + "\""); // 窗口标题
//            command.add("/B"); // 后台运行
            command.add("cmd.exe");
            command.add("/c");
            command.add(fullCommand.toString());

            pb = new ProcessBuilder(command);
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            // Linux/Unix: 使用 nohup 命令确保完全独立运行
            List<String> command = new ArrayList<>();
            command.add("nohup");
            command.add("java");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Dsun.stdout.encoding=UTF-8");
            command.add("-Dsun.stderr.encoding=UTF-8");

            if (project.getJvmOpts() != null && !project.getJvmOpts().isEmpty()) {
                String[] jvmOptsArray = project.getJvmOpts().trim().split("\\s+");
                Collections.addAll(command, jvmOptsArray);
            }

            command.add("-jar");
            command.add(project.getTargetJar());
            command.add("--server.port=" + port);
            command.add("--spring.profiles.active=" + profile);
            command.add(" " + project.getOtherOpts());
            command.add("&");

            pb = new ProcessBuilder(command);
        } else if (os.contains("mac")) {
            // macOS: 使用类似Linux的方式
            List<String> command = new ArrayList<>();
            command.add("nohup");
            command.add("java");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Dsun.stdout.encoding=UTF-8");
            command.add("-Dsun.stderr.encoding=UTF-8");

            if (project.getJvmOpts() != null && !project.getJvmOpts().isEmpty()) {
                String[] jvmOptsArray = project.getJvmOpts().trim().split("\\s+");
                Collections.addAll(command, jvmOptsArray);
            }

            command.add("-jar");
            command.add(project.getTargetJar());
            command.add("--server.port=" + port);
            command.add("--spring.profiles.active=" + profile);
            command.add(" " + project.getOtherOpts());
            command.add("&");

            pb = new ProcessBuilder(command);
        } else {
            // 其他系统使用标准方式，但确保独立运行
            List<String> command = new ArrayList<>();
            command.add("java");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Dsun.stdout.encoding=UTF-8");
            command.add("-Dsun.stderr.encoding=UTF-8");

            if (project.getJvmOpts() != null && !project.getJvmOpts().isEmpty()) {
                String[] jvmOptsArray = project.getJvmOpts().trim().split("\\s+");
                Collections.addAll(command, jvmOptsArray);
            }

            command.add("-jar");
            command.add(project.getTargetJar());
            command.add("--server.port=" + port);
            command.add("--spring.profiles.active=" + profile);
            command.add(" " + project.getOtherOpts());
            pb = new ProcessBuilder(command);
        }

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
        // 设置字符编码环境变量
        env.put("LANG", "zh_CN.UTF-8");
        env.put("LC_ALL", "zh_CN.UTF-8");

        // 启动进程
        Process process = pb.start();

        // 对于Windows系统，立即断开与进程的连接以确保独立性
        if (os.contains("win")) {
            try {
                // 短暂等待确认命令已发送
                Thread.sleep(100);
                // 关闭流以断开连接
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            } catch (Exception e) {
                // 忽略关闭流时的异常
            }
        }
        appendLog("已启动独立进程，PID: " + (process != null ? process.pid() : "unknown") + "启动指令:" + pb.command());
        return process;
    }

    // 日志输出辅助方法
    private void appendLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logArea.appendText("[" + timestamp + "] " + message + "\n");
    }

    // 进程输出辅助方法
    private void appendProcessOutput(String message) {
        Platform.runLater(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
            processOutputArea.appendText("[" + timestamp + "] " + message + "\n");
        });
    }

    // 错误提示
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("错误");
        alert.setContentText(message);
        alert.showAndWait();
    }

    // 流读取器（用于捕获进程输出）
    private static class StreamGobbler implements Runnable {
        private final InputStream inputStream;
        private final Consumer<String> consumer;

        public StreamGobbler(InputStream inputStream, Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer = consumer;
        }

        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines().forEach(consumer);
        }
    }
}
