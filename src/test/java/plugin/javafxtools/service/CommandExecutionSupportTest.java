package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandExecutionSupportTest {
    @Test
    void truncatesCapturedOutputWhileStillDrainingTheChildProcess() throws Exception {
        var result = CommandExecutionSupport.execute(
                javaCommand(OutputProcess.class), Duration.ofSeconds(5),
                StandardCharsets.UTF_8, 128);

        assertTrue(result.successful());
        assertTrue(result.truncated());
        assertEquals(128, result.output().length());
    }

    @Test
    void timesOutAChildProcessWithinTheConfiguredBoundary() {
        long startedAt = System.nanoTime();

        IOException exception = assertThrows(IOException.class,
                () -> CommandExecutionSupport.execute(
                        sleepCommand(), Duration.ofMillis(150),
                        StandardCharsets.UTF_8, 128));

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        assertTrue(exception.getMessage().contains("超时"));
        assertTrue(elapsedMillis < 3_000, "超时后应及时终止子进程");
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        String executable = WindowsProcessSupport.isWindows() ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                mainClass.getName());
    }

    private static List<String> sleepCommand() {
        return WindowsProcessSupport.isWindows()
                ? List.of("cmd.exe", "/c", "ping.exe", "-n", "10", "127.0.0.1")
                : List.of("sh", "-c", "sleep 10");
    }

    public static final class OutputProcess {
        private OutputProcess() {
        }

        public static void main(String[] args) {
            System.out.print("x".repeat(4_096));
        }
    }

}
