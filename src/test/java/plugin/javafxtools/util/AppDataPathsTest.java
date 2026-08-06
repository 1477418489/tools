package plugin.javafxtools.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppDataPathsTest {
    @TempDir
    Path tempDirectory;

    @Test
    void windowsUsesLocalAppDataDirectory() {
        Path localAppData = tempDirectory.resolve("LocalAppData");

        Path result = AppDataPaths.resolveDataDirectory(
                "Windows 11", localAppData.toString(), tempDirectory.toString());

        assertEquals(localAppData.resolve("FxTools").toAbsolutePath().normalize(), result);
    }

    @Test
    void windowsFallsBackToUserLocalDirectory() {
        Path result = AppDataPaths.resolveDataDirectory(
                "Windows 11", "", tempDirectory.toString());

        assertEquals(tempDirectory.resolve("AppData").resolve("Local").resolve("FxTools")
                .toAbsolutePath().normalize(), result);
    }

    @Test
    void nonWindowsUsesHiddenUserDirectory() {
        Path result = AppDataPaths.resolveDataDirectory(
                "Linux", null, tempDirectory.toString());

        assertEquals(tempDirectory.resolve(".fxtools").toAbsolutePath().normalize(), result);
    }

    @Test
    void dataFileRejectsNestedPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> AppDataPaths.dataFile("nested/config.json"));
    }
}
