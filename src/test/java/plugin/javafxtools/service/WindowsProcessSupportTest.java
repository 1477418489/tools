package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsProcessSupportTest {
    @Test
    void parsesOnlyExactTaskListImageMatches() {
        List<String> lines = List.of(
                "\"java.exe\",\"1200\",\"Console\",\"1\",\"20,000 K\"",
                "\"javaw.exe\",\"1300\",\"Console\",\"1\",\"18,000 K\"",
                "INFO: No tasks are running which match the specified criteria.",
                "\"java.exe\",\"1400\",\"Console\",\"1\",\"22,000 K\""
        );

        assertEquals(List.of(1200L, 1400L),
                WindowsProcessSupport.parseTaskListProcessIds(lines, "java.exe"));
    }

    @Test
    void parsesOneTaskListSnapshotForAllImages() {
        List<String> lines = List.of(
                "\"java.exe\",\"1200\",\"Console\",\"1\",\"20,000 K\"",
                "\"javaw.exe\",\"1300\",\"Console\",\"1\",\"18,000 K\"",
                "\"java.exe\",\"1400\",\"Console\",\"1\",\"22,000 K\"",
                "\"my,app.exe\",\"1500\",\"Console\",\"1\",\"12,000 K\"",
                "INFO: No tasks are running which match the specified criteria."
        );

        assertEquals(Map.of(
                        "java.exe", List.of(1200L, 1400L),
                        "javaw.exe", List.of(1300L),
                        "my,app.exe", List.of(1500L)),
                WindowsProcessSupport.parseTaskListSnapshot(lines));
    }

    @Test
    void normalizesImageNamesForSnapshotLookup() {
        assertEquals("java.exe", WindowsProcessSupport.normalizeImageName(" JAVA "));
        assertEquals("javaw.exe", WindowsProcessSupport.normalizeImageName("JavaW.exe"));
    }

}
