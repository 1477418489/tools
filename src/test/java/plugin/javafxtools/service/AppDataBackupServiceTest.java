package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDataBackupServiceTest {
    @TempDir
    Path tempDirectory;

    private final AppDataBackupService service = new AppDataBackupService();

    @Test
    void exportsRegularFilesWithRelativePaths() throws Exception {
        Path dataDirectory = Files.createDirectory(tempDirectory.resolve("data"));
        Files.writeString(dataDirectory.resolve("settings.json"), "{\"sound\":true}",
                StandardCharsets.UTF_8);
        Path nested = Files.createDirectory(dataDirectory.resolve("nested"));
        Files.writeString(nested.resolve("items.json"), "[1,2]", StandardCharsets.UTF_8);
        Path destination = tempDirectory.resolve("exports").resolve("backup.zip");

        AppDataBackupService.BackupResult result = service.export(dataDirectory, destination);

        assertEquals(destination.toAbsolutePath().normalize(), result.archive());
        assertEquals(2, result.fileCount());
        assertEquals(Map.of(
                "settings.json", "{\"sound\":true}",
                "nested/items.json", "[1,2]"), readEntries(destination));
    }

    @Test
    void emptyDataDirectoryProducesValidEmptyArchive() throws Exception {
        Path dataDirectory = Files.createDirectory(tempDirectory.resolve("data"));
        Path destination = tempDirectory.resolve("backup.zip");

        AppDataBackupService.BackupResult result = service.export(dataDirectory, destination);

        assertEquals(0, result.fileCount());
        assertTrue(readEntries(destination).isEmpty());
    }

    @Test
    void destinationInsideDataDirectoryIsRejected() throws Exception {
        Path dataDirectory = Files.createDirectory(tempDirectory.resolve("data"));
        Path destination = dataDirectory.resolve("backup.zip");

        IOException failure = assertThrows(IOException.class,
                () -> service.export(dataDirectory, destination));

        assertTrue(failure.getMessage().contains("不能保存在应用数据目录内"));
        assertFalse(Files.exists(destination));
    }

    @Test
    void existingBackupIsAtomicallyReplaced() throws Exception {
        Path dataDirectory = Files.createDirectory(tempDirectory.resolve("data"));
        Files.writeString(dataDirectory.resolve("settings.json"), "new", StandardCharsets.UTF_8);
        Path destination = tempDirectory.resolve("backup.zip");
        Files.writeString(destination, "old", StandardCharsets.UTF_8);

        service.export(dataDirectory, destination);

        assertEquals(Map.of("settings.json", "new"), readEntries(destination));
    }

    @Test
    void validationFailureLeavesNoTemporaryArchive() throws Exception {
        Path dataDirectory = Files.createDirectory(tempDirectory.resolve("data"));
        Path destination = Files.createDirectory(tempDirectory.resolve("backup.zip"));

        assertThrows(IOException.class, () -> service.export(dataDirectory, destination));

        try (var paths = Files.list(tempDirectory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".fxtools-backup-")));
        }
    }

    private Map<String, String> readEntries(Path archive) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                try (var input = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return entries;
    }
}
