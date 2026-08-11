package plugin.javafxtools;

import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.controller.LogMonitorController;

import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogMonitorFxmlTest {
    private static final String FXML_NAMESPACE = "http://javafx.com/fxml/1";
    private static final Path FXML = Path.of(
            "src", "main", "resources", "plugin", "javafxtools", "log-monitor-view.fxml");

    @Test
    void pageContainsTheCompleteMonitorWorkspaceContract() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(FXML.toFile());
        Element root = document.getDocumentElement();

        assertEquals("http://javafx.com/javafx/23", root.getNamespaceURI());
        assertEquals("plugin.javafxtools.controller.LogMonitorController",
                root.getAttributeNS(FXML_NAMESPACE, "controller"));

        Set<String> requiredIds = Set.of(
                "logFileField", "chooseFileButton", "saveConfigButton",
                "startMonitorButton", "stopMonitorButton", "monitorStatusLabel",
                "ruleNameField", "ruleExpressionField", "matchModeComboBox",
                "caseSensitiveCheckBox", "ruleEnabledCheckBox", "addRuleButton",
                "updateRuleButton", "deleteRuleButton", "ruleTable",
                "ruleNameColumn", "ruleExpressionColumn", "ruleModeColumn",
                "ruleCaseColumn", "ruleEnabledColumn", "matchTable",
                "matchTimeColumn", "matchRuleColumn", "matchExpressionColumn",
                "matchLineColumn", "matchCountLabel", "logViewer");
        for (String id : requiredIds) {
            assertTrue(findById(document, id) != null, () -> "Missing fx:id=" + id);
        }

        Map<String, String> handlers = Map.of(
                "chooseFileButton", "#selectLogFile",
                "saveConfigButton", "#saveConfig",
                "startMonitorButton", "#startMonitoring",
                "stopMonitorButton", "#stopMonitoring",
                "addRuleButton", "#addRule",
                "updateRuleButton", "#updateRule",
                "deleteRuleButton", "#deleteRule");
        handlers.forEach((id, handler) ->
                assertEquals(handler, findById(document, id).getAttribute("onAction"), id));
    }

    @Test
    void controllerExposesItsLifecycleAndApplicationDependencies() throws Exception {
        assertTrue(BaseController.class.isAssignableFrom(LogMonitorController.class));
        assertPublicMethod("initialize");
        assertPublicMethod("cleanup");
        assertPublicMethod("setPrimaryStage", Stage.class);
        assertPublicMethod("setReminderSoundEnabledSupplier", BooleanSupplier.class);
    }

    private static Element findById(Document document, String id) {
        NodeList elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttributeNS(FXML_NAMESPACE, "id"))) {
                return element;
            }
        }
        return null;
    }

    private static void assertPublicMethod(String name, Class<?>... parameterTypes) throws Exception {
        assertTrue(Modifier.isPublic(
                LogMonitorController.class.getMethod(name, parameterTypes).getModifiers()), name);
    }
}
