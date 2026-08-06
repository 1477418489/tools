package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.model.ProjectConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarFileServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void blankOptionalLibPathsOnlyCopyTheJar() throws Exception {
        Path sourceJar = tempDirectory.resolve("source.jar");
        Path targetJar = tempDirectory.resolve("output").resolve("app.jar");
        Files.writeString(sourceJar, "jar-content", StandardCharsets.UTF_8);
        ProjectConfig project = project(sourceJar, targetJar);
        project.setSourceLib("");
        project.setLibTarget("  ");

        new JarFileService(_ -> { }).copyProjectFiles(project);

        assertEquals("jar-content", Files.readString(targetJar, StandardCharsets.UTF_8));
    }

    @Test
    void overlappingLibDirectoriesAreRejectedBeforeAnyFileIsWritten() throws Exception {
        Path sourceJar = tempDirectory.resolve("source.jar");
        Path targetJar = tempDirectory.resolve("output").resolve("app.jar");
        Path sourceLib = tempDirectory.resolve("lib");
        Path nestedTarget = sourceLib.resolve("deployed");
        Files.writeString(sourceJar, "jar-content", StandardCharsets.UTF_8);
        Files.createDirectories(sourceLib);
        Path sentinel = sourceLib.resolve("dependency.jar");
        Files.writeString(sentinel, "dependency", StandardCharsets.UTF_8);
        ProjectConfig project = project(sourceJar, targetJar);
        project.setSourceLib(sourceLib.toString());
        project.setLibTarget(nestedTarget.toString());

        JarFileService service = new JarFileService(_ -> { });
        assertThrows(IOException.class, () -> service.copyProjectFiles(project));

        assertTrue(Files.exists(sentinel));
        assertFalse(Files.exists(targetJar));
    }

    private ProjectConfig project(Path sourceJar, Path targetJar) {
        ProjectConfig project = new ProjectConfig();
        project.setSourceJar(sourceJar.toString());
        project.setTargetJar(targetJar.toString());
        return project;
    }
}
