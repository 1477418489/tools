package plugin.javafxtools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxmlLayoutRegressionTest {
    @Test
    void mainViewUsesVerticalSplitPaneForResizableSystemLog() throws IOException {
        String fxml = readFxml("main-view.fxml");

        assertTrue(fxml.contains("<SplitPane orientation=\"VERTICAL\""));
        assertTrue(fxml.contains("fx:id=\"centralLogArea\""));
        assertTrue(fxml.contains("styleClass=\"system-log-panel\""));
    }

    @Test
    void jarLauncherRemovesUnusedProcessOutputArea() throws IOException {
        String fxml = readFxml("jar-launcher-view.fxml");

        assertFalse(fxml.contains("processOutputArea"));
        assertFalse(fxml.contains("进程输出"));
    }

    private static String readFxml(String fileName) throws IOException {
        return Files.readString(Path.of("src", "main", "resources",
                "plugin", "javafxtools", fileName));
    }
}
