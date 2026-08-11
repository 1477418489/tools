package plugin.javafxtools.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs bounded PowerShell scripts without placing the script body on the command line. */
final class PowerShellScriptRunner {
    private static final int OUTPUT_READ_TIMEOUT_SECONDS = 2;
    private static final int MAX_OUTPUT_BYTES = 1_048_576;
    private static final String SCRIPT_LAUNCHER =
            "$encoded=[Console]::In.ReadToEnd();"
                    + "$script=[Text.Encoding]::Unicode.GetString("
                    + "[Convert]::FromBase64String($encoded));"
                    + "& ([ScriptBlock]::Create($script));";

    private PowerShellScriptRunner() {
    }

    static Result run(String script, int timeoutSeconds,
                      String operationName, String outputThreadName) throws IOException {
        Objects.requireNonNull(script, "script");
        String encoded = Base64.getEncoder().encodeToString(
                script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", SCRIPT_LAUNCHER)
                .redirectErrorStream(true)
                .start();
        FutureTask<byte[]> outputTask = new FutureTask<>(() -> {
            byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
            if (output.length > MAX_OUTPUT_BYTES) {
                process.destroyForcibly();
                throw new IOException(operationName + "输出超过 1 MiB 限制");
            }
            return output;
        });
        Thread.ofVirtual().name(outputThreadName).start(outputTask);
        try {
            try (OutputStream input = process.getOutputStream()) {
                input.write(encoded.getBytes(StandardCharsets.US_ASCII));
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminate(process, outputTask);
                throw new IOException(operationName + "超时");
            }
            String output = new String(
                    outputTask.get(OUTPUT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    StandardCharsets.UTF_8);
            return new Result(process.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminate(process, outputTask);
            throw new IOException(operationName + "被中断", e);
        } catch (IOException e) {
            terminate(process, outputTask);
            throw e;
        } catch (ExecutionException e) {
            terminate(process, outputTask);
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("无法读取" + operationName + "结果", e.getCause());
        } catch (TimeoutException e) {
            terminate(process, outputTask);
            throw new IOException("读取" + operationName + "结果超时", e);
        }
    }

    private static void terminate(Process process, FutureTask<?> outputTask) {
        outputTask.cancel(true);
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    record Result(int exitCode, String output) {
    }
}
