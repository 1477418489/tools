package plugin.javafxtools.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashSet;
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
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 获取占用端口的进程信息。
     *
     * @param port 端口号
     * @return 进程信息
     */
    public String getProcessUsingPort(int port) {
        try {
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
        } finally {
            waitForProcessExit(nameProcess, 3);
        }
        return processInfo.length() > 0 ? "PID:" + pid + " " + processInfo : "PID:" + pid;
    }

    private Set<String> findListeningPidsByPort(int port) throws IOException {
        Set<String> pids = new LinkedHashSet<>();
        ProcessBuilder getPidPb = new ProcessBuilder("cmd.exe", "/c", "netstat -ano -p tcp");
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
}
