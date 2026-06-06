package plugin.javafxtools.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Windows 外部进程命令封装。
 *
 * @author wwj
 */
public final class WindowsProcessSupport {
    /**
     * taskkill 命令最长等待秒数。
     */
    private static final int TASKKILL_TIMEOUT_SECONDS = 6;

    private WindowsProcessSupport() {
    }

    /**
     * 判断当前系统是否为 Windows。
     *
     * @return Windows 返回 true
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 创建可见窗口启动命令。
     *
     * @param path 可执行文件路径
     * @return 进程构建器
     */
    public static ProcessBuilder createVisibleProcessBuilder(String path) {
        File execFile = new File(path);
        String workingDirectory = execFile.getParent();
        if (workingDirectory == null || workingDirectory.isBlank()) {
            workingDirectory = new File(".").getAbsoluteFile().getParent();
        }
        return new ProcessBuilder(
                "cmd.exe",
                "/c",
                "start",
                "",
                "/wait",
                "/D",
                workingDirectory,
                execFile.getAbsolutePath()
        );
    }

    /**
     * 按 PID 终止 Windows 进程树。
     *
     * @param pid 根进程 PID
     * @return 是否成功
     * @throws IOException 命令执行异常
     * @throws InterruptedException 等待中断
     */
    public static boolean killProcessTreeByPid(long pid) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "taskkill.exe",
                "/PID",
                String.valueOf(pid),
                "/T",
                "/F"
        );
        pb.redirectErrorStream(true);
        Process killProcess = pb.start();
        try {
            boolean finished = killProcess.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return finished && killProcess.exitValue() == 0;
        } finally {
            if (killProcess.isAlive()) {
                killProcess.destroyForcibly();
            }
        }
    }

    /**
     * 按镜像名检查进程是否存在。
     *
     * @param processName 进程名
     * @return 是否运行
     * @throws IOException 命令执行异常
     */
    public static boolean isProcessRunning(String processName) throws IOException {
        if (processName == null || processName.trim().isEmpty()) {
            return false;
        }

        String searchName = normalizeImageName(processName);
        ProcessBuilder pb = new ProcessBuilder(
                "cmd", "/c",
                "tasklist /FI \"IMAGENAME eq " + searchName + "\" /FO CSV /NH"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "GBK"))) {
            return reader.lines()
                    .filter(line -> !line.trim().isEmpty())
                    .anyMatch(line -> firstCsvFieldEquals(line, searchName));
        } finally {
            waitAndDestroy(process, 3);
        }
    }

    /**
     * 按镜像名终止进程树。
     *
     * @param processName 进程名
     * @return 是否成功
     * @throws IOException 命令执行异常
     */
    public static boolean killProcessByImageName(String processName) throws IOException {
        if (processName == null || processName.trim().isEmpty()) {
            return false;
        }

        String targetName = normalizeImageName(processName);
        ProcessBuilder pb = new ProcessBuilder(
                "cmd", "/c",
                "taskkill /F /IM \"" + targetName + "\" /T"
        );
        pb.redirectErrorStream(true);

        Process killProcess = pb.start();
        try {
            return killProcess.waitFor(5, TimeUnit.SECONDS) && killProcess.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (killProcess.isAlive()) {
                killProcess.destroyForcibly();
            }
        }
    }

    private static String normalizeImageName(String processName) {
        String imageName = processName.toLowerCase();
        return imageName.endsWith(".exe") ? imageName : imageName + ".exe";
    }

    private static boolean firstCsvFieldEquals(String line, String expected) {
        String[] fields = line.split(",");
        if (fields.length == 0) {
            return false;
        }
        String procName = fields[0].replace("\"", "").trim().toLowerCase();
        return procName.equals(expected);
    }

    private static void waitAndDestroy(Process process, int timeoutSeconds) {
        try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
