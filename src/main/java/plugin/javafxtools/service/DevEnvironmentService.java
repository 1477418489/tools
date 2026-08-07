package plugin.javafxtools.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Performs an on-demand health check of common local development tools. */
public final class DevEnvironmentService {
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final Charset NATIVE_CHARSET = Charset.forName(
            System.getProperty("native.encoding", "UTF-8"));
    private static final List<ToolDefinition> TOOLS = List.of(
            new ToolDefinition("jdk", "JDK 编译器", "javac", List.of("-version"), true),
            new ToolDefinition("maven", "Apache Maven", "mvn", List.of("--version"), true),
            new ToolDefinition("git", "Git", "git", List.of("--version"), true),
            new ToolDefinition("node", "Node.js", "node", List.of("--version"), false),
            new ToolDefinition("npm", "npm", "npm", List.of("--version"), false),
            new ToolDefinition("python", "Python", "python", List.of("--version"), false),
            new ToolDefinition("docker", "Docker CLI", "docker", List.of("--version"), false)
    );

    public EnvironmentReport inspect() throws IOException {
        List<CheckResult> results = new ArrayList<>();
        results.add(runtimeResult());
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<CheckResult>> futures = TOOLS.stream()
                    .map(tool -> executor.submit(() -> inspectTool(tool)))
                    .toList();
            for (Future<CheckResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("环境体检已中断", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    results.add(new CheckResult("unknown", "未知工具", CheckStatus.ERROR,
                            "检测失败", "", errorMessage(cause), false));
                }
            }
        } finally {
            executor.shutdownNow();
        }
        long available = results.stream()
                .filter(result -> result.status() == CheckStatus.AVAILABLE).count();
        long requiredIssues = results.stream()
                .filter(CheckResult::recommended)
                .filter(result -> result.status() != CheckStatus.AVAILABLE).count();
        return new EnvironmentReport(results, (int) available, (int) requiredIssues);
    }

    private CheckResult runtimeResult() {
        String version = System.getProperty("java.runtime.version",
                System.getProperty("java.version", "未知版本"));
        String vendor = System.getProperty("java.vendor", "未知发行方");
        String home = System.getProperty("java.home", "");
        return new CheckResult("runtime", "FxTools Java 运行时", CheckStatus.AVAILABLE,
                version, home, vendor + " · " + System.getProperty("os.arch", ""), false);
    }

    private CheckResult inspectTool(ToolDefinition tool) {
        Optional<Path> executable = findExecutable(tool.command());
        if (executable.isEmpty() && tool.id().equals("python")) {
            executable = findExecutable("py");
        }
        if (executable.isEmpty()) {
            return new CheckResult(tool.id(), tool.name(), CheckStatus.MISSING,
                    "未发现", "", tool.recommended()
                    ? "未在 PATH 中找到，建议配置后重新检测" : "未安装或未加入 PATH",
                    tool.recommended());
        }

        List<String> command = new ArrayList<>();
        command.add(executable.get().toString());
        command.addAll(tool.arguments());
        try {
            CommandExecutionSupport.CommandResult result = CommandExecutionSupport.execute(
                    command, TOOL_TIMEOUT, NATIVE_CHARSET, MAX_OUTPUT_BYTES);
            String firstLine = firstMeaningfulLine(result.output());
            if (result.successful()) {
                String configurationIssue = configurationIssue(tool, executable.get());
                if (configurationIssue != null) {
                    return new CheckResult(tool.id(), tool.name(), CheckStatus.WARNING,
                            firstLine.isBlank() ? "可执行" : firstLine,
                            executable.get().toString(), configurationIssue,
                            tool.recommended());
                }
                return new CheckResult(tool.id(), tool.name(), CheckStatus.AVAILABLE,
                        firstLine.isBlank() ? "可用" : firstLine,
                        executable.get().toString(),
                        result.truncated() ? "输出过长，已截断" : "命令执行正常",
                        tool.recommended());
            }
            return new CheckResult(tool.id(), tool.name(), CheckStatus.ERROR,
                    "命令异常", executable.get().toString(),
                    firstLine.isBlank() ? "退出码 " + result.exitCode() : firstLine,
                    tool.recommended());
        } catch (IOException e) {
            return new CheckResult(tool.id(), tool.name(), CheckStatus.ERROR,
                    "无法执行", executable.get().toString(), errorMessage(e),
                    tool.recommended());
        }
    }

    Optional<Path> findExecutable(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        Set<String> extensions = executableExtensions();
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return Optional.empty();
        }
        for (String directory : pathValue.split(File.pathSeparator)) {
            String cleaned = stripQuotes(directory.strip());
            if (cleaned.isBlank()) {
                continue;
            }
            for (String extension : extensions) {
                try {
                    Path candidate = Path.of(cleaned, command + extension);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate.toAbsolutePath().normalize());
                    }
                } catch (InvalidPathException ignored) {
                    // Ignore malformed PATH entries and continue checking the rest.
                }
            }
        }
        return Optional.empty();
    }

    private Set<String> executableExtensions() {
        Set<String> extensions = new LinkedHashSet<>();
        if (WindowsProcessSupport.isWindows()) {
            String pathExt = System.getenv("PATHEXT");
            if (pathExt != null) {
                for (String extension : pathExt.split(";")) {
                    String normalized = extension.strip().toLowerCase(Locale.ROOT);
                    if (!normalized.isBlank()) {
                        extensions.add(normalized.startsWith(".")
                                ? normalized : "." + normalized);
                    }
                }
            }
            extensions.addAll(List.of(".com", ".exe", ".bat", ".cmd"));
        }
        extensions.add("");
        return extensions;
    }

    private String configurationIssue(ToolDefinition tool, Path executable) {
        if (!tool.id().equals("jdk")) {
            return null;
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }
        try {
            Path configuredHome = Path.of(stripQuotes(javaHome.strip()))
                    .toAbsolutePath().normalize();
            Path resolvedExecutable = executable.toAbsolutePath().normalize();
            boolean matches = resolvedExecutable.startsWith(configuredHome);
            return matches ? null : "PATH 中的 javac 与 JAVA_HOME 不一致：" + configuredHome;
        } catch (InvalidPathException e) {
            return "JAVA_HOME 不是有效路径：" + javaHome;
        }
    }

    private static String stripQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    static String firstMeaningfulLine(String output) {
        if (output == null) {
            return "";
        }
        return output.lines().map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst().orElse("");
    }

    private static String errorMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.isBlank()
                ? (throwable == null ? "未知错误" : throwable.getClass().getSimpleName())
                : message;
    }

    public enum CheckStatus {
        AVAILABLE, WARNING, MISSING, ERROR
    }

    public record CheckResult(String id, String name, CheckStatus status, String version,
                              String path, String detail, boolean recommended) {
    }

    public record EnvironmentReport(List<CheckResult> results, int availableCount,
                                    int requiredIssueCount) {
        public EnvironmentReport {
            results = List.copyOf(results);
        }
    }

    private record ToolDefinition(String id, String name, String command,
                                  List<String> arguments, boolean recommended) {
    }
}
