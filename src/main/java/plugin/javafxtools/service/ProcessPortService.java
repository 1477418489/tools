package plugin.javafxtools.service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Captures a point-in-time Windows process and listening-port snapshot. */
public final class ProcessPortService {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(6);
    private static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final Charset NATIVE_CHARSET = Charset.forName(
            System.getProperty("native.encoding", "GBK"));

    public boolean isSupported() {
        return WindowsProcessSupport.isWindows();
    }

    public Snapshot capture() throws IOException {
        if (!isSupported()) {
            throw new IOException("进程与端口中心仅支持 Windows");
        }
        List<String> warnings = new ArrayList<>();
        Map<Long, TaskProcess> taskProcesses;
        try {
            CommandExecutionSupport.CommandResult taskListResult =
                    CommandExecutionSupport.execute(
                            List.of("tasklist.exe", "/FO", "CSV", "/NH"),
                            COMMAND_TIMEOUT, NATIVE_CHARSET, MAX_OUTPUT_BYTES);
            if (taskListResult.successful()) {
                taskProcesses = parseTaskList(taskListResult.output().lines().toList());
                if (taskProcesses.isEmpty()) {
                    warnings.add("tasklist 未返回可解析的进程，已使用受限快照");
                    taskProcesses = captureProcessHandleFallback();
                }
            } else {
                warnings.add(commandFailure("tasklist", taskListResult).getMessage());
                taskProcesses = captureProcessHandleFallback();
            }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            warnings.add("tasklist 不可用：" + e.getMessage());
            taskProcesses = captureProcessHandleFallback();
        }

        List<PortEntry> parsedPorts;
        try {
            CommandExecutionSupport.CommandResult netstatResult =
                    CommandExecutionSupport.execute(
                            List.of("netstat.exe", "-ano", "-p", "tcp"),
                            COMMAND_TIMEOUT, NATIVE_CHARSET, MAX_OUTPUT_BYTES);
            if (netstatResult.successful()) {
                parsedPorts = parseNetstat(netstatResult.output().lines().toList());
            } else {
                warnings.add(commandFailure("netstat", netstatResult).getMessage());
                parsedPorts = List.of();
            }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            warnings.add("netstat 不可用：" + e.getMessage());
            parsedPorts = List.of();
        }
        Map<Long, Set<Integer>> portsByPid = new HashMap<>();
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("系统快照读取已中断");
        }
        for (PortEntry port : parsedPorts) {
            portsByPid.computeIfAbsent(port.pid(), ignored -> new LinkedHashSet<>())
                    .add(port.port());
        }

        List<ProcessEntry> processes = taskProcesses.values().stream()
                .map(process -> enrich(process, portsByPid.getOrDefault(process.pid(), Set.of())))
                .sorted(Comparator.comparing(ProcessEntry::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(ProcessEntry::pid))
                .toList();
        Map<Long, String> namesByPid = new HashMap<>();
        processes.forEach(process -> namesByPid.put(process.pid(), process.name()));
        List<PortEntry> ports = parsedPorts.stream()
                .map(port -> new PortEntry(port.protocol(), port.address(), port.port(), port.pid(),
                        namesByPid.getOrDefault(port.pid(), "未知进程")))
                .sorted(Comparator.comparingInt(PortEntry::port)
                        .thenComparingLong(PortEntry::pid))
                .toList();
        return new Snapshot(processes, ports, Instant.now(), warnings);
    }

    public void terminateProcess(long pid, String expectedName) throws IOException {
        if (!isSupported()) {
            throw new IOException("终止进程仅支持 Windows");
        }
        if (pid <= 4 || pid == ProcessHandle.current().pid()) {
            throw new IOException("不允许终止系统关键进程或 FxTools 自身");
        }
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.get().isAlive()) {
            return;
        }
        if (!isSpecificProcessName(expectedName)) {
            throw new IOException("无法安全确认 PID " + pid + " 的进程身份，请刷新后重试");
        }
        String currentCommand = process.get().info().command().orElse("");
        String currentName = currentCommand.isBlank()
                ? queryProcessName(pid).orElseThrow(() -> new IOException(
                "无法验证 PID " + pid + " 的当前进程身份，已拒绝终止"))
                : fileName(currentCommand);
        if (!currentName.equalsIgnoreCase(expectedName.strip())) {
            throw new IOException("进程快照已过期：PID " + pid + " 当前属于 "
                    + currentName + "，请刷新后重试");
        }
        try {
            if (!WindowsProcessSupport.killProcessTreeByPid(pid)) {
                throw new IOException("Windows 未能终止 PID " + pid
                        + "，可能需要管理员权限");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("终止进程操作已中断", e);
        }
    }

    static Map<Long, TaskProcess> parseTaskList(List<String> lines) {
        Map<Long, TaskProcess> processes = new LinkedHashMap<>();
        for (String line : lines) {
            List<String> fields = parseCsvLine(line);
            if (fields.size() < 5) {
                continue;
            }
            try {
                long pid = Long.parseLong(fields.get(1).strip());
                long memoryBytes = parseMemoryBytes(fields.get(4));
                processes.put(pid, new TaskProcess(pid, fields.get(0).strip(), memoryBytes));
            } catch (NumberFormatException ignored) {
                // Ignore tasklist headings and localized error output.
            }
        }
        return Map.copyOf(processes);
    }

    static List<PortEntry> parseNetstat(List<String> lines) {
        List<PortEntry> ports = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length < 5 || !fields[0].equalsIgnoreCase("TCP")
                    || !fields[3].equalsIgnoreCase("LISTENING")) {
                continue;
            }
            Endpoint endpoint = parseEndpoint(fields[1]);
            if (endpoint == null) {
                continue;
            }
            try {
                long pid = Long.parseLong(fields[fields.length - 1]);
                ports.add(new PortEntry("TCP", endpoint.address(), endpoint.port(), pid, ""));
            } catch (NumberFormatException ignored) {
                // Ignore malformed or incomplete netstat rows.
            }
        }
        return List.copyOf(ports);
    }

    private static ProcessEntry enrich(TaskProcess process, Set<Integer> ports) {
        Optional<ProcessHandle> handle = ProcessHandle.of(process.pid());
        ProcessHandle.Info info = handle.map(ProcessHandle::info).orElse(null);
        String user = info == null ? "" : info.user().orElse("");
        String command = info == null ? "" : info.commandLine()
                .or(() -> info.command()).orElse("");
        long cpuSeconds = info == null ? -1L
                : info.totalCpuDuration().map(Duration::toSeconds).orElse(-1L);
        return new ProcessEntry(process.pid(), process.name(), user, process.memoryBytes(),
                cpuSeconds, command, List.copyOf(ports));
    }

    private static Map<Long, TaskProcess> captureProcessHandleFallback() {
        Map<Long, TaskProcess> processes = new LinkedHashMap<>();
        try (var handles = ProcessHandle.allProcesses()) {
            handles.forEach(handle -> {
                ProcessHandle.Info info = handle.info();
                String command = info.command().orElse("");
                String name = command.isBlank() ? "PID " + handle.pid() : fileName(command);
                processes.put(handle.pid(), new TaskProcess(handle.pid(), name, -1L));
            });
        }
        return Map.copyOf(processes);
    }

    private static String fileName(String command) {
        try {
            java.nio.file.Path name = java.nio.file.Path.of(command).getFileName();
            return name == null ? command : name.toString();
        } catch (RuntimeException e) {
            int separator = Math.max(command.lastIndexOf('\\'), command.lastIndexOf('/'));
            return separator >= 0 ? command.substring(separator + 1) : command;
        }
    }

    private static boolean isSpecificProcessName(String processName) {
        return processName != null && !processName.isBlank()
                && !processName.equals("未知进程") && !processName.startsWith("PID ");
    }

    private static Optional<String> queryProcessName(long pid) throws IOException {
        CommandExecutionSupport.CommandResult result = CommandExecutionSupport.execute(
                List.of("tasklist.exe", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH"),
                COMMAND_TIMEOUT, NATIVE_CHARSET, 256 * 1024);
        if (!result.successful()) {
            return Optional.empty();
        }
        TaskProcess process = parseTaskList(result.output().lines().toList()).get(pid);
        return process == null ? Optional.empty() : Optional.of(process.name());
    }

    private static long parseMemoryBytes(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0L : Long.parseLong(digits) * 1024L;
    }

    private static Endpoint parseEndpoint(String value) {
        int separator = value.lastIndexOf(':');
        if (separator < 0 || separator == value.length() - 1) {
            return null;
        }
        try {
            int port = Integer.parseInt(value.substring(separator + 1));
            String address = value.substring(0, separator);
            if (address.startsWith("[") && address.endsWith("]")) {
                address = address.substring(1, address.length() - 1);
            }
            return port >= 0 && port <= 65_535 ? new Endpoint(address, port) : null;
        } catch (NumberFormatException e) {
            return null;
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

    private static IOException commandFailure(
            String command, CommandExecutionSupport.CommandResult result) {
        String detail = result.output().isBlank() ? "没有返回详情" : result.output();
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "...";
        }
        return new IOException(command + " 执行失败（退出码 " + result.exitCode() + "）：" + detail);
    }

    public record Snapshot(List<ProcessEntry> processes, List<PortEntry> ports,
                           Instant capturedAt, List<String> warnings) {
        public Snapshot {
            processes = List.copyOf(processes);
            ports = List.copyOf(ports);
            warnings = List.copyOf(warnings);
        }
    }

    public record ProcessEntry(long pid, String name, String user, long memoryBytes,
                               long cpuSeconds, String command, List<Integer> ports) {
        public ProcessEntry {
            ports = List.copyOf(ports);
        }
    }

    public record PortEntry(String protocol, String address, int port, long pid,
                            String processName) {
    }

    record TaskProcess(long pid, String name, long memoryBytes) {
    }

    private record Endpoint(String address, int port) {
    }
}
