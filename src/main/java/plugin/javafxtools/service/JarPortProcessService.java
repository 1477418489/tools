package plugin.javafxtools.service;

import plugin.javafxtools.model.ProjectConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * JAR 启动器端口检查和端口占用进程终止逻辑。
 *
 * @author wwj
 */
public class JarPortProcessService {
    private static final int PORT_RELEASE_WAIT_ATTEMPTS = 20;
    private static final int PORT_RELEASE_WAIT_MS = 300;
    private static final int TASKKILL_TIMEOUT_SECONDS = 8;

    private final Consumer<String> logger;
    private final Consumer<String> errorReporter;

    /**
     * 创建端口进程服务。
     *
     * @param logger 日志输出回调
     * @param errorReporter 错误提示回调
     */
    public JarPortProcessService(Consumer<String> logger, Consumer<String> errorReporter) {
        this.logger = logger;
        this.errorReporter = errorReporter;
    }

    /**
     * 检查本机端口是否被占用。
     *
     * @param port 端口号
     * @return 是否占用
     */
    public boolean checkPortInUse(int port) {
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 检查端口监听进程是否属于指定 JAR 项目。
     *
     * @param project 项目配置
     * @param port 项目端口
     * @return 端口归属检查结果
     */
    public ProjectPortInspection inspectProjectPort(ProjectConfig project, int port) {
        if (project == null) {
            throw new IllegalArgumentException("项目不能为空");
        }
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
        }
        if (!checkPortInUse(port)) {
            return new ProjectPortInspection(ProjectPortState.FREE, Set.of(), Set.of());
        }

        try {
            Set<String> listeningPids = findListeningPidsByPort(port);
            if (listeningPids.isEmpty()) {
                return new ProjectPortInspection(
                        ProjectPortState.OCCUPIED, Set.of(), Set.of());
            }

            Set<String> projectPids = new LinkedHashSet<>();
            Set<String> otherPids = new LinkedHashSet<>();
            for (String pid : listeningPids) {
                String commandLine = readProcessCommandLine(pid);
                if (commandLineTargetsJar(commandLine, project.getTargetJar())) {
                    projectPids.add(pid);
                } else {
                    otherPids.add(pid);
                }
            }
            ProjectPortState state = projectPids.isEmpty()
                    ? ProjectPortState.OCCUPIED
                    : ProjectPortState.PROJECT_RUNNING;
            return new ProjectPortInspection(
                    state, Set.copyOf(projectPids), Set.copyOf(otherPids));
        } catch (IOException e) {
            logger.accept("无法识别端口 " + port + " 的监听进程: " + e.getMessage());
            return new ProjectPortInspection(ProjectPortState.OCCUPIED, Set.of(), Set.of());
        }
    }

    /**
     * 只终止属于指定项目的端口监听进程，拒绝处理其他程序。
     *
     * @param project 项目配置
     * @param port 项目端口
     * @return 项目进程已停止或原本未运行
     */
    public boolean killProjectOnPort(ProjectConfig project, int port) {
        ProjectPortInspection inspection = inspectProjectPort(project, port);
        if (inspection.state() == ProjectPortState.FREE) {
            return true;
        }
        if (inspection.state() != ProjectPortState.PROJECT_RUNNING) {
            logger.accept("端口 " + port + " 由其他进程占用，已拒绝终止");
            return false;
        }

        boolean killedAll = true;
        for (String pid : inspection.projectPids()) {
            try {
                killedAll &= killProcessTree(pid, port);
            } catch (IOException e) {
                logger.accept("终止项目进程失败 (PID: " + pid + "): " + e.getMessage());
                killedAll = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return killedAll
                && inspectProjectPort(project, port).state() != ProjectPortState.PROJECT_RUNNING;
    }

    /**
     * 判断端口号是否处于 TCP/UDP 有效范围。
     *
     * @param port 端口号
     * @return 是否有效
     */
    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    /**
     * 获取占用端口的进程信息。
     *
     * @param port 端口号
     * @return 进程信息
     */
    public String getProcessUsingPort(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder("netstat.exe", "-ano");
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
            } finally {
                waitForProcessExit(process, 3);
            }

            if (pid != null) {
                return describeProcess(pid);
            }
        } catch (Exception e) {
            logger.accept("获取进程信息时出错: " + e.getMessage());
        }
        return "未知进程";
    }

    /**
     * 终止占用端口的监听进程树，并等待端口释放。
     *
     * @param port 端口号
     * @return 是否成功触发终止并释放端口
     */
    public boolean killProcessOnPort(int port) {
        try {
            boolean killedAny = false;
            for (int attempt = 1; attempt <= 3; attempt++) {
                Set<String> pids = findListeningPidsByPort(port);
                if (pids.isEmpty()) {
                    if (attempt == 1) {
                        logger.accept("未找到占用端口 " + port + " 的 LISTENING 进程");
                    }
                    return !checkPortInUse(port);
                }

                for (String pid : pids) {
                    killedAny |= killProcessTree(pid, port);
                }

                if (waitForPortRelease(port)) {
                    logger.accept("端口 " + port + " 已释放");
                    return killedAny;
                }

                logger.accept("端口 " + port + " 仍被占用，重新查询占用进程...");
            }
            return false;
        } catch (Exception e) {
            errorReporter.accept("终止进程失败: " + e.getMessage());
            return false;
        }
    }

    private String describeProcess(String pid) throws IOException {
        ProcessBuilder namePb = new ProcessBuilder(
                "wmic.exe",
                "process",
                "where", "processid=" + pid,
                "get", "name,commandline",
                "/FORMAT:LIST");
        Process nameProcess = namePb.start();
        List<String> outputLines = new ArrayList<>();
        try (BufferedReader nameReader = new BufferedReader(
                new InputStreamReader(nameProcess.getInputStream(), "GBK"))) {
            String nameLine;
            while ((nameLine = nameReader.readLine()) != null) {
                outputLines.add(nameLine);
            }
        } finally {
            waitForProcessExit(nameProcess, 3);
        }
        return formatProcessDetails(pid, outputLines);
    }

    private String readProcessCommandLine(String pid) throws IOException {
        try {
            long processId = Long.parseLong(pid);
            String commandLine = ProcessHandle.of(processId)
                    .flatMap(handle -> handle.info().commandLine())
                    .orElse("");
            if (!commandLine.isBlank()) {
                return commandLine;
            }
        } catch (NumberFormatException ignored) {
            // 继续使用系统命令读取详情。
        }

        ProcessBuilder commandLineBuilder = new ProcessBuilder(
                "wmic.exe",
                "process",
                "where", "processid=" + pid,
                "get", "commandline",
                "/FORMAT:LIST");
        Process commandLineProcess = commandLineBuilder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(commandLineProcess.getInputStream(), "GBK"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("CommandLine=")) {
                    return trimmed.substring("CommandLine=".length()).trim();
                }
            }
        } finally {
            waitForProcessExit(commandLineProcess, 3);
        }
        return "";
    }

    static boolean commandLineTargetsJar(String commandLine, String targetJar) {
        if (commandLine == null || commandLine.isBlank()
                || targetJar == null || targetJar.isBlank()) {
            return false;
        }
        try {
            String normalizedTarget = targetJar.trim().replace('/', '\\');
            boolean windowsAbsolute = normalizedTarget.matches("^[A-Za-z]:\\\\.*")
                    || normalizedTarget.startsWith("\\\\");
            if (!windowsAbsolute) {
                normalizedTarget = Path.of(normalizedTarget)
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                        .replace('/', '\\');
            }
            normalizedTarget = normalizedTarget.toLowerCase(java.util.Locale.ROOT);
            String normalizedCommand = commandLine
                    .replace('/', '\\')
                    .toLowerCase(java.util.Locale.ROOT);
            return normalizedCommand.contains(normalizedTarget);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    static String formatProcessDetails(String pid, List<String> wmicLines) {
        String processName = "";
        String commandLine = "";

        for (String line : wmicLines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("Name=")) {
                processName = trimmed.substring("Name=".length()).trim();
            } else if (trimmed.startsWith("CommandLine=")) {
                commandLine = trimmed.substring("CommandLine=".length()).trim();
            }
        }

        StringBuilder details = new StringBuilder("PID:").append(pid);
        if (!processName.isEmpty()) {
            details.append("，进程名: ").append(processName);
        }
        if (!commandLine.isEmpty()) {
            details.append("，命令行: ").append(commandLine);
        }
        return details.toString();
    }

    private Set<String> findListeningPidsByPort(int port) throws IOException {
        Set<String> pids = new LinkedHashSet<>();
        ProcessBuilder getPidPb = new ProcessBuilder("netstat.exe", "-ano", "-p", "tcp");
        Process getPidProcess = getPidPb.start();
        String portSuffix = ":" + port + " ";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getPidProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(portSuffix) && line.contains("LISTENING")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 0) {
                        pids.add(parts[parts.length - 1]);
                    }
                }
            }
        } finally {
            waitForProcessExit(getPidProcess, 3);
        }
        return pids;
    }

    private boolean killProcessTree(String pid, int port) throws IOException, InterruptedException {
        ProcessBuilder killPb = new ProcessBuilder("taskkill.exe", "/PID", pid, "/T", "/F");
        killPb.redirectErrorStream(true);
        Process killProcess = killPb.start();
        boolean finished = killProcess.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            killProcess.destroyForcibly();
            logger.accept("终止进程树超时 (PID: " + pid + ")");
            return false;
        }

        int result = killProcess.exitValue();
        if (result == 0) {
            logger.accept("成功终止占用端口 " + port + " 的进程树 (PID: " + pid + ")");
            return true;
        }
        logger.accept("终止进程树失败 (PID: " + pid + ", exitCode: " + result + ")");
        return false;
    }

    private boolean waitForPortRelease(int port) throws InterruptedException {
        for (int i = 0; i < PORT_RELEASE_WAIT_ATTEMPTS; i++) {
            if (!checkPortInUse(port)) {
                return true;
            }
            Thread.sleep(PORT_RELEASE_WAIT_MS);
        }
        return !checkPortInUse(port);
    }

    private void waitForProcessExit(Process process, int timeoutSeconds) {
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    public enum ProjectPortState {
        FREE,
        PROJECT_RUNNING,
        OCCUPIED
    }

    public record ProjectPortInspection(ProjectPortState state,
                                        Set<String> projectPids,
                                        Set<String> otherPids) {
    }
}
