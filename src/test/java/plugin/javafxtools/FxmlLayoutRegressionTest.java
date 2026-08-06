package plugin.javafxtools;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void mainViewUsesCollapsedSystemLogToPreserveWorkspace() throws IOException {
        String fxml = readFxml("main-view.fxml");

        assertTrue(fxml.contains("fx:id=\"systemLogPane\""));
        assertTrue(fxml.contains("expanded=\"false\""));
        assertTrue(fxml.contains("fx:id=\"centralLogArea\""));
        assertTrue(fxml.contains("styleClass=\"system-log-pane\""));
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
    void jarLauncherRemovesUnusedProcessOutputArea() throws IOException {
        String fxml = readFxml("jar-launcher-view.fxml");

        assertFalse(fxml.contains("processOutputArea"));
        assertFalse(fxml.contains("进程输出"));
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
                "websocket-view.fxml");

        for (String view : toolViews) {
            String fxml = readFxml(view);
            assertTrue(fxml.contains("styleClass=\"tool-page\""), view);
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
