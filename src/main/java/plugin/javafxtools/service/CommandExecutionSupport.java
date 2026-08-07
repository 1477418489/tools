package plugin.javafxtools.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes a bounded local command without leaving reader threads or child processes behind. */
public final class CommandExecutionSupport {
    private static final int BUFFER_SIZE = 8_192;
    private static final long DESCENDANT_SNAPSHOT_TIMEOUT_MILLIS = 200;
    private static final long TERMINATION_WAIT_MILLIS = 500;

    private CommandExecutionSupport() {
    }

    public static CommandResult execute(List<String> command,
                                        Duration timeout,
                                        Charset charset,
                                        int maxOutputBytes) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("命令不能为空");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("超时时间必须大于 0");
        }
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("输出上限必须大于 0");
        }

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        FutureTask<CapturedOutput> outputTask = new FutureTask<>(
                () -> capture(process.getInputStream(), maxOutputBytes));
        Thread outputReader = Thread.ofVirtual()
                .name("command-output-reader")
                .start(outputTask);

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("命令执行超时（" + timeout.toSeconds() + " 秒）");
            }
            CapturedOutput captured = outputTask.get(1, TimeUnit.SECONDS);
            return new CommandResult(process.exitValue(),
                    new String(captured.bytes(), charset).strip(), captured.truncated());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令执行被中断", e);
        } catch (ExecutionException e) {
            throw new IOException("读取命令输出失败", e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("读取命令输出超时", e);
        } finally {
            if (process.isAlive()) {
                List<ProcessHandle> descendants = captureDescendants(process);
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                waitForTermination(process);
            }
            if (!process.isAlive()) {
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                    // The process may have closed the stream first.
                }
            }
            outputReader.interrupt();
        }
    }

    private static List<ProcessHandle> captureDescendants(Process process) {
        FutureTask<List<ProcessHandle>> snapshotTask = new FutureTask<>(() -> {
            try (var descendants = process.descendants()) {
                return descendants.toList();
            }
        });
        Thread snapshotThread = Thread.ofVirtual()
                .name("command-descendant-snapshot")
                .start(snapshotTask);
        try {
            return snapshotTask.get(DESCENDANT_SNAPSHOT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | TimeoutException e) {
            return List.of();
        } finally {
            snapshotTask.cancel(true);
            snapshotThread.interrupt();
        }
    }

    private static void waitForTermination(Process process) {
        try {
            process.waitFor(TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static CapturedOutput capture(InputStream input, int maxOutputBytes)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(maxOutputBytes, 64 * 1024));
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int remaining = maxOutputBytes - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(read, remaining));
            }
            total += read;
        }
        return new CapturedOutput(output.toByteArray(), total > maxOutputBytes);
    }

    public record CommandResult(int exitCode, String output, boolean truncated) {
        public boolean successful() {
            return exitCode == 0;
        }
    }

    private record CapturedOutput(byte[] bytes, boolean truncated) {
    }
}
