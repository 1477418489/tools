package plugin.javafxtools.service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 通过当前用户注册表管理 Windows 登录启动项。
 */
public final class WindowsStartupService {
    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "FxTools";

    public boolean isSupported() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public void setEnabled(boolean enabled) throws IOException {
        if (!isSupported()) {
            throw new IOException("当前系统不支持 Windows 启动项");
        }
        if (!enabled && !registryValueExists()) {
            return;
        }
        List<String> command = enabled
                ? List.of("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ",
                        "/d", currentLaunchCommand(), "/f")
                : List.of("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        byte[] output;
        try {
            output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String message = new String(output, Charset.defaultCharset()).trim();
                throw new IOException(message.isEmpty()
                        ? "启动项更新失败，退出码: " + exitCode : message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("启动项更新被中断", e);
        }
    }

    private boolean registryValueExists() throws IOException {
        Process process = new ProcessBuilder(
                "reg", "query", RUN_KEY, "/v", VALUE_NAME)
                .redirectErrorStream(true)
                .start();
        try {
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("启动项查询被中断", e);
        }
    }

    String currentLaunchCommand() throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command()
                .orElseThrow(() -> new IOException("无法确定当前程序启动路径"));
        List<String> parts = new ArrayList<>();
        parts.add(Path.of(executable).toAbsolutePath().normalize().toString());
        parts.addAll(Arrays.asList(info.arguments().orElse(new String[0])));
        return parts.stream().map(WindowsStartupService::quoteArgument)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
    }

    static String quoteArgument(String value) {
        if (value != null && !value.isEmpty()
                && value.chars().noneMatch(ch -> Character.isWhitespace(ch) || ch == '"')) {
            return value;
        }
        String input = value == null ? "" : value;
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current == '\\') {
                backslashes++;
            } else if (current == '"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                quoted.append("\\".repeat(backslashes)).append(current);
                backslashes = 0;
            }
        }
        quoted.append("\\".repeat(backslashes * 2)).append('"');
        return quoted.toString();
    }
}
