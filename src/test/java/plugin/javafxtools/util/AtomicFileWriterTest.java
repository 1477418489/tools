package plugin.javafxtools.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AtomicFileWriterTest {
    @TempDir
    Path tempDirectory;

    @Test
    void writesUtf8AndReplacesExistingContent() throws Exception {
        Path target = tempDirectory.resolve("nested").resolve("config.json");

        AtomicFileWriter.writeUtf8(target, "旧内容");
        AtomicFileWriter.writeUtf8(target, "{\"name\":\"新配置\"}");

        assertEquals("{\"name\":\"新配置\"}",
                Files.readString(target, StandardCharsets.UTF_8));
        try (var files = Files.list(target.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
