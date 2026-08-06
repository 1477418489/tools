package plugin.javafxtools.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final int TASKLIST_TIMEOUT_SECONDS = 3;
    private static final Charset WINDOWS_COMMAND_CHARSET = Charset.forName(
            System.getProperty("native.encoding", "GBK"));

    private WindowsProcessSupport() {
    }

    /**
     * 判断当前系统是否为 Windows。
     *
     * @return Windows 返回 true
     */
    public static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
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
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
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
        return !findProcessIdsByImageName(processName).isEmpty();
    }

    /**
     * 获取当前 Windows 进程列表快照。一次状态检查只需执行一次 tasklist。
     *
     * @return 以规范化镜像名为键的 PID 列表
     * @throws IOException 命令执行异常
     */
    public static Map<String, List<Long>> captureProcessSnapshot() throws IOException {
        return parseTaskListSnapshot(runTaskList("/FO", "CSV", "/NH"));
    }

    /**
     * 查找指定镜像名的全部进程 PID。
     *
     * @param processName 进程名
     * @return 匹配的进程 PID
     * @throws IOException 命令执行异常
     */
    public static List<Long> findProcessIdsByImageName(String processName) throws IOException {
        if (processName == null || processName.trim().isEmpty()) {
            return List.of();
        }

        String searchName = normalizeImageName(processName);
        return parseTaskListProcessIds(runTaskList(
                "/FI", "IMAGENAME eq " + searchName,
                "/FO", "CSV",
                "/NH"), searchName);
    }

    static String normalizeImageName(String processName) {
        String imageName = processName.trim().toLowerCase(Locale.ROOT);
        return imageName.endsWith(".exe") ? imageName : imageName + ".exe";
    }

    static Map<String, List<Long>> parseTaskListSnapshot(List<String> lines) {
        Map<String, List<Long>> processIdsByName = new HashMap<>();
        for (String line : lines) {
            List<String> fields = parseCsvLine(line);
            if (fields.size() < 2) {
                continue;
            }
            String processName = fields.get(0).trim().toLowerCase(Locale.ROOT);
            try {
                long processId = Long.parseLong(fields.get(1).trim());
                processIdsByName.computeIfAbsent(processName, ignored -> new ArrayList<>())
                        .add(processId);
            } catch (NumberFormatException ignored) {
                // 忽略 tasklist 返回的标题或错误信息。
            }
        }
        processIdsByName.replaceAll((name, processIds) -> List.copyOf(processIds));
        return Map.copyOf(processIdsByName);
    }

    static List<Long> parseTaskListProcessIds(List<String> lines, String expectedName) {
        List<Long> processIds = new ArrayList<>();
        for (String line : lines) {
            List<String> fields = parseCsvLine(line);
            if (fields.size() < 2) {
                continue;
            }
            String processName = fields.get(0).trim().toLowerCase(Locale.ROOT);
            if (!processName.equals(expectedName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                processIds.add(Long.parseLong(fields.get(1).trim()));
            } catch (NumberFormatException ignored) {
                // 忽略 tasklist 返回的非数据行。
            }
        }
        return processIds;
    }

    private static List<String> runTaskList(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("tasklist.exe");
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        FutureTask<List<String>> outputTask = new FutureTask<>(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), WINDOWS_COMMAND_CHARSET))) {
                return reader.lines().toList();
            }
        });
        Thread outputReader = Thread.ofVirtual()
                .name("tasklist-output-reader")
                .start(outputTask);

        try {
            if (!process.waitFor(TASKLIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("tasklist 执行超时");
            }
            List<String> output = outputTask.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                String detail = summarizeOutput(output);
                throw new IOException("tasklist 执行失败，退出码: " + process.exitValue()
                        + (detail.isEmpty() ? "" : "，输出: " + detail));
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tasklist 执行被中断", e);
        } catch (ExecutionException e) {
            throw new IOException("读取 tasklist 输出失败", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("读取 tasklist 输出超时", e);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
                // 进程结束期间输出流可能已经关闭。
            }
            outputReader.interrupt();
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static String summarizeOutput(List<String> output) {
        String summary = String.join(" ", output).strip();
        return summary.length() <= 240 ? summary : summary.substring(0, 240) + "...";
    }
}
