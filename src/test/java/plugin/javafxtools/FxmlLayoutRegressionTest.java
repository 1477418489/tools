package plugin.javafxtools;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import plugin.javafxtools.controller.MainController;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxmlLayoutRegressionTest {
    private static final String FXML_NAMESPACE = "http://javafx.com/fxml/1";
    private static final Path FXML_DIRECTORY = Path.of(
            "src", "main", "resources", "plugin", "javafxtools");

    @Test
    void allFxmlDocumentsAreWellFormedAndUseJavaFx23Namespace() throws Exception {
        List<Path> fxmlFiles;
        try (var files = Files.list(FXML_DIRECTORY)) {
            fxmlFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(".fxml"))
                    .sorted()
                    .toList();
        }
        assertFalse(fxmlFiles.isEmpty());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        for (Path fxmlFile : fxmlFiles) {
            Document document = factory.newDocumentBuilder().parse(fxmlFile.toFile());
            assertEquals("http://javafx.com/javafx/23",
                    document.getDocumentElement().getNamespaceURI(),
                    fxmlFile.toString());
        }
    }

    @Test
    void mainViewUsesPopupOnlySystemLogToPreserveWorkspace() throws IOException {
        String fxml = readFxml("main-view.fxml");

        assertTrue(fxml.contains("fx:id=\"systemLogViewer\""));
        assertTrue(fxml.contains("title=\"系统日志\""));
        assertFalse(fxml.contains("systemLogPane"));
        assertFalse(fxml.contains("<TitledPane"));
        assertFalse(fxml.contains("inlineContent=\"true\""));
    }

    @Test
    void mainViewDeclaresLazyModuleTabsAndRuntimeSettings() throws IOException {
        String fxml = readFxml("main-view.fxml");

        assertFalse(fxml.contains("<fx:include"));
        assertTrue(fxml.contains("fx:id=\"appLauncherTab\""));
        assertTrue(fxml.contains("fx:id=\"memoReminderTab\""));
        assertTrue(fxml.contains("fx:id=\"windowsPowerTab\""));
        assertTrue(fxml.contains("fx:id=\"closeToTrayMenuItem\""));
        assertTrue(fxml.contains("fx:id=\"reminderSoundMenuItem\""));
        assertTrue(fxml.contains("fx:id=\"startupMenuItem\""));
        assertTrue(fxml.contains("onAction=\"#handleOpenDataDirectory\""));
        assertTrue(fxml.contains("fx:id=\"exportBackupMenuItem\""));
        assertTrue(fxml.contains("onAction=\"#handleExportDataBackup\""));
    }

    @Test
    void everyLazyModuleResourceIsAvailableOnTheRuntimeClasspath() {
        for (String resource : List.of(
                "app-launcher-view.fxml",
                "http-request-view.fxml",
                "websocket-view.fxml",
                "network-tools-view.fxml",
                "data-format-view.fxml",
                "strData-format-view.fxml",
                "jar-launcher-view.fxml",
                "memo-reminder-view.fxml",
                "keepalive-manager-view.fxml",
                "windows-power-view.fxml")) {
            assertNotNull(MainController.class.getResource("/plugin/javafxtools/" + resource),
                    resource);
        }
        assertNotNull(MainController.class.getResource("/css/modern-light.css"));
    }

    @Test
    void operationalLogViewsUseTheUnifiedViewer() throws IOException {
        List<String> logViews = List.of(
                "app-launcher-view.fxml",
                "http-request-view.fxml",
                "jar-launcher-view.fxml",
                "keepalive-manager-view.fxml",
                "memo-reminder-view.fxml",
                "websocket-view.fxml");

        for (String view : logViews) {
            String fxml = readFxml(view);
            assertTrue(fxml.contains("<LogViewer"), view);
            assertFalse(fxml.contains("fx:id=\"logArea\""), view);
            assertFalse(fxml.contains("fx:id=\"wsMessageArea\""), view);
        }
    }

    @Test
    void ordinaryLogsArePopupOnlyAndWorkAreasTakeRemainingHeight() throws IOException {
        for (String view : List.of(
                "app-launcher-view.fxml",
                "http-request-view.fxml",
                "keepalive-manager-view.fxml",
                "memo-reminder-view.fxml")) {
            String fxml = readFxml(view);
            assertFalse(fxml.contains("<SplitPane orientation=\"VERTICAL\""), view);
            assertFalse(fxml.contains("expanded="), view);
            assertFalse(fxml.contains("inlineContent=\"true\""), view);
            assertTrue(fxml.contains("VBox.vgrow=\"ALWAYS\""), view);
        }
    }

    @Test
    void httpRequestAndResponseWorkspacesUseAvailableWidthAndHeight() throws IOException {
        String fxml = readFxml("http-request-view.fxml");

        assertTrue(fxml.contains("<ColumnConstraints percentWidth=\"44\""));
        assertTrue(fxml.contains("<ColumnConstraints percentWidth=\"56\""));
        assertTrue(fxml.contains("fx:id=\"responseBodyArea\""));
        assertTrue(fxml.contains("fx:id=\"responseHeadersArea\""));
        assertTrue(fxml.contains("fx:id=\"sendOnceButton\""));
        assertTrue(fxml.contains("onAction=\"#handleSendOnceButton\""));
        assertTrue(fxml.contains("GridPane.columnIndex=\"1\""));
    }

    @Test
    void websocketKeepsItsOperationalMessageStreamInline() throws IOException {
        String fxml = readFxml("websocket-view.fxml");

        assertTrue(fxml.contains("fx:id=\"wsMessageViewer\""));
        assertTrue(fxml.contains("inlineContent=\"true\""));
        assertTrue(fxml.contains("VBox.vgrow=\"ALWAYS\""));
    }

    @Test
    void memoReminderProvidesIntervalAndAtTimeSchedules() throws IOException {
        String fxml = readFxml("memo-reminder-view.fxml");

        assertTrue(fxml.contains("fx:id=\"intervalModeButton\""));
        assertTrue(fxml.contains("fx:id=\"atTimeModeButton\""));
        assertTrue(fxml.contains("fx:id=\"reminderDatePicker\""));
        assertTrue(fxml.contains("fx:id=\"reminderTimeField\""));
        assertTrue(fxml.contains("fx:id=\"scheduleCol\""));
    }

    @Test
    void jarLauncherShowsAllProjectRuntimeStatesWithoutAnInlineLog() throws IOException {
        String fxml = readFxml("jar-launcher-view.fxml");

        assertTrue(fxml.contains("fx:id=\"projectListView\""));
        assertTrue(fxml.contains("fx:id=\"projectOverviewLabel\""));
        assertTrue(fxml.contains("fx:id=\"statusRefreshTimeLabel\""));
        assertTrue(fxml.contains("onAction=\"#refreshProjectStatuses\""));
        assertTrue(fxml.contains("fx:id=\"openDirectoryButton\""));
        assertTrue(fxml.contains("onAction=\"#handleOpenTargetDirectory\""));
        assertTrue(fxml.contains("fx:id=\"openLogButton\""));
        assertTrue(fxml.contains("onAction=\"#handleOpenCurrentLog\""));
        assertFalse(fxml.contains("fx:id=\"projectComboBox\""));
        assertFalse(fxml.contains("processOutputArea"));
        assertFalse(fxml.contains("进程输出"));
        assertFalse(fxml.contains("inlineContent=\"true\""));
    }

    @Test
    void windowsPowerPageProvidesPersistentPowerAndWakeSchedules() throws IOException {
        String fxml = readFxml("windows-power-view.fxml");

        assertTrue(fxml.contains("fx:id=\"powerActionComboBox\""));
        assertTrue(fxml.contains("onAction=\"#handleSchedulePower\""));
        assertTrue(fxml.contains("onAction=\"#handleCancelPower\""));
        assertTrue(fxml.contains("onAction=\"#handleScheduleWake\""));
        assertTrue(fxml.contains("onAction=\"#handleCancelWake\""));
        assertTrue(fxml.contains("onAction=\"#handleRefreshDiagnostics\""));
        assertTrue(fxml.contains("fx:id=\"diagnosticsStatusLabel\""));
        assertTrue(fxml.contains("fx:id=\"biosInterfaceStatusLabel\""));
        assertTrue(fxml.contains("fx:id=\"wakeTimerStatusLabel\""));
        assertTrue(fxml.contains("RTC 定时开机"));
        assertTrue(fxml.contains("需在固件中确认"));
    }

    @Test
    void everyFxmlActionHandlerExistsOnItsController() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        try (var files = Files.list(FXML_DIRECTORY)) {
            for (Path fxmlFile : files.filter(path -> path.toString().endsWith(".fxml")).toList()) {
                Document document = factory.newDocumentBuilder().parse(fxmlFile.toFile());
                Element root = document.getDocumentElement();
                String controllerName = root.getAttributeNS(FXML_NAMESPACE, "controller");
                if (controllerName.isBlank()) {
                    continue;
                }
                Class<?> controllerType = Class.forName(controllerName);
                assertActionHandlersExist(root, controllerType, fxmlFile);
            }
        }
    }

    @Test
    void everyToolPageUsesTheSharedPageHeader() throws IOException {
        Set<String> toolViews = Set.of(
                "app-launcher-view.fxml",
                "data-format-view.fxml",
                "http-request-view.fxml",
                "jar-launcher-view.fxml",
                "keepalive-manager-view.fxml",
                "memo-reminder-view.fxml",
                "network-tools-view.fxml",
                "strData-format-view.fxml",
                "websocket-view.fxml",
                "windows-power-view.fxml");

        for (String view : toolViews) {
            String fxml = readFxml(view);
            assertTrue(fxml.contains("tool-page"), view);
            assertTrue(fxml.contains("styleClass=\"page-header\""), view);
            assertTrue(fxml.contains("styleClass=\"page-title\""), view);
        }
    }

    private static void assertActionHandlersExist(Element element,
                                                  Class<?> controllerType,
                                                  Path fxmlFile) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String value = attribute.getNodeValue();
            if (attribute.getNodeName().startsWith("on") && value.startsWith("#")) {
                String methodName = value.substring(1);
                assertTrue(hasMethod(controllerType, methodName),
                        () -> fxmlFile + " references missing handler #" + methodName);
            }
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                assertActionHandlersExist(child, controllerType, fxmlFile);
            }
        }
    }

    private static boolean hasMethod(Class<?> type, String methodName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (var method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String readFxml(String fileName) throws IOException {
        return Files.readString(FXML_DIRECTORY.resolve(fileName));
    }
}
