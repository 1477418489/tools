package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JarStartupLogTailerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void readsOnlyCurrentLaunchAndPreservesUtf8PartialLines() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "previous launch\n", StandardCharsets.UTF_8);
        long startOffset = Files.size(log);
        JarStartupLogTailer tailer = new JarStartupLogTailer(log, startOffset);

        append(log, "应用启动中\n尚未完成");

        assertEquals(List.of("应用启动中"), tailer.readAvailable(false));

        append(log, "，继续\n");

        assertEquals(List.of("尚未完成，继续"), tailer.readAvailable(false));
    }

    @Test
    void flushesFinalPartialLineAndRemovesAnsiControlSequences() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "\u001B[32mStarted successfully\u001B[0m",
                StandardCharsets.UTF_8);
        JarStartupLogTailer tailer = new JarStartupLogTailer(log, 0);

        assertEquals(List.of("Started successfully"), tailer.readAvailable(true));
        assertEquals(List.of(), tailer.readAvailable(true));
    }

    private void append(Path log, String value) throws Exception {
        Files.writeString(log, value, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
