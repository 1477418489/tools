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
    void mainViewUsesGroupedSidebarNavigationWithLazyModuleTabs() throws IOException {
        String fxml = readFxml("main-view.fxml");

        assertFalse(fxml.contains("<fx:include"));
        assertTrue(fxml.contains("styleClass=\"app-sidebar\""));
        assertTrue(fxml.contains("fx:id=\"activeSectionLabel\""));
        assertTrue(fxml.contains("fx:id=\"activeModuleLabel\""));
        assertTrue(fxml.contains("fx:id=\"appLauncherNavButton\""));
        assertTrue(fxml.contains("fx:id=\"httpRequestNavButton\""));
        assertTrue(fxml.contains("fx:id=\"networkQualityNavButton\""));
        assertTrue(fxml.contains("fx:id=\"jarLauncherNavButton\""));
        assertTrue(fxml.contains("fx:id=\"memoReminderNavButton\""));
        assertTrue(fxml.contains("fx:id=\"windowsPowerNavButton\""));
        assertTrue(fxml.contains("fx:id=\"processPortNavButton\""));
        assertTrue(fxml.contains("fx:id=\"devEnvironmentNavButton\""));
        assertTrue(fxml.contains("fx:id=\"fileAnalysisNavButton\""));
        assertTrue(fxml.contains("fx:id=\"base64NavButton\""));
        assertTrue(fxml.contains("styleClass=\"sidebar-scroll\""));
        assertTrue(fxml.contains("fx:id=\"appLauncherTab\""));
        assertTrue(fxml.contains("fx:id=\"memoReminderTab\""));
        assertTrue(fxml.contains("fx:id=\"windowsPowerTab\""));
        assertTrue(fxml.contains("fx:id=\"networkQualityTab\""));
        assertFalse(fxml.contains("styleClass=\"app-header\""));
    }

    @Test
    void mainViewKeepsRuntimeSettingsInTheWorkspaceCommandBar() throws IOException {
        String fxml = readFxml("main-view.fxml");

        assertTrue(fxml.contains("styleClass=\"workspace-header\""));
        assertTrue(fxml.contains("fx:id=\"closeToTrayMenuItem\""));
        assertTrue(fxml.contains("fx:id=\"reminderSoundMenuItem\""));
        assertTrue(fxml.contains("fx:id=\"startupMenuItem\""));
        assertTrue(fxml.contains("onAction=\"#handleOpenDataDirectory\""));
        assertTrue(fxml.contains("fx:id=\"exportBackupMenuItem\""));
        assertTrue(fxml.contains("onAction=\"#handleExportDataBackup\""));
    }

    @Test
    void hiddenModuleTabsDoNotHideNestedEditorOrPowerTabs() throws IOException {
        String structuralTheme = Files.readString(Path.of(
                "src", "main", "resources", "css", "styles.css"));
        String visualTheme = Files.readString(Path.of(
                "src", "main", "resources", "css", "modern-light.css"));

        for (String theme : List.of(structuralTheme, visualTheme)) {
            assertTrue(theme.contains(".toolbox-tab-pane > .tab-header-area"));
            assertTrue(theme.contains(".toolbox-tab-pane > .tab-content-area"));
            assertFalse(theme.contains(".toolbox-tab-pane .tab-header-area"));
            assertFalse(theme.contains(".toolbox-tab-pane .tab-content-area"));
        }
    }

    @Test
    void everyLazyModuleResourceIsAvailableOnTheRuntimeClasspath() {
        for (String resource : List.of(
                "app-launcher-view.fxml",
                "http-request-view.fxml",
                "websocket-view.fxml",
                "network-tools-view.fxml",
                "network-quality-view.fxml",
                "process-port-view.fxml",
                "dev-environment-view.fxml",
                "file-analysis-view.fxml",
                "data-format-view.fxml",
                "strData-format-view.fxml",
                "base64-view.fxml",
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
        assertTrue(fxml.contains("<RowConstraints percentHeight=\"100\""));
    }

    @Test
    void websocketKeepsItsOperationalMessageStreamInline() throws IOException {
        String fxml = readFxml("websocket-view.fxml");

        assertTrue(fxml.contains("fx:id=\"wsMessageViewer\""));
        assertTrue(fxml.contains("inlineContent=\"true\""));
        assertTrue(fxml.contains("VBox.vgrow=\"ALWAYS\""));
    }

    @Test
    void networkDiagnosticsSupportsDomainsIpsPortsAndStructuredResults() throws IOException {
        String fxml = readFxml("network-tools-view.fxml");

        assertTrue(fxml.contains("fx:id=\"hostField\""));
        assertTrue(fxml.contains("fx:id=\"portComboBox\""));
        assertTrue(fxml.contains("fx:id=\"timeoutComboBox\""));
        assertTrue(fxml.contains("onAction=\"#handleCheckAll\""));
        assertTrue(fxml.contains("onAction=\"#handleResolve\""));
        assertTrue(fxml.contains("onAction=\"#handlePortCheck\""));
        assertTrue(fxml.contains("onAction=\"#handleIpLookup\""));
        assertTrue(fxml.contains("onAction=\"#handlePublicIpLookup\""));
        assertTrue(fxml.contains("onAction=\"#handleCancel\""));
        assertTrue(fxml.contains("fx:id=\"addressListView\""));
        assertTrue(fxml.contains("fx:id=\"detailArea\""));
        assertTrue(fxml.contains("fx:id=\"ipInfoTab\""));
        assertTrue(fxml.contains("fx:id=\"ipLocationValueLabel\""));
        assertTrue(fxml.contains("fx:id=\"ipNetworkValueLabel\""));
        assertFalse(fxml.contains("fx:id=\"lookupResultArea\""));
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
    void jarLauncherShowsAllProjectRuntimeStatesWithLiveInlineLog() throws IOException {
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
        assertTrue(fxml.contains("inlineContent=\"true\""));
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
        assertTrue(fxml.contains("fx:id=\"deviceBrandSummaryLabel\""));
        assertTrue(fxml.contains("fx:id=\"processorSummaryLabel\""));
        assertTrue(fxml.contains("fx:id=\"processorMetaSummaryLabel\""));
        assertTrue(fxml.contains("fx:id=\"graphicsSummaryLabel\""));
        assertTrue(fxml.contains("fx:id=\"graphicsMetaSummaryLabel\""));
        assertTrue(fxml.contains("fx:id=\"memorySummaryLabel\""));
        assertTrue(fxml.contains("fx:id=\"memoryMetaSummaryLabel\""));
        assertFalse(fxml.contains("fx:id=\"temperatureSummaryLabel\""));
        assertTrue(fxml.contains("fx:id=\"temperatureValueLabel\""));
        assertTrue(fxml.contains("fx:id=\"biosSummaryLabel\""));
        assertTrue(fxml.contains("fx:id=\"biosDateSummaryLabel\""));
        assertTrue(fxml.contains("fx:id=\"activePowerPlanValueLabel\""));
        assertTrue(fxml.contains("fx:id=\"powerSupplyStatusValueLabel\""));
        assertTrue(fxml.contains("fx:id=\"serialNumberValueLabel\""));
        assertTrue(fxml.contains("fx:id=\"systemUuidValueLabel\""));
        assertTrue(fxml.contains("diagnostic-hardware-grid"));
        assertTrue(fxml.contains("diagnostics-capability-grid"));
        assertTrue(fxml.contains("BIOS 发布日期"));
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
                "network-quality-view.fxml",
                "process-port-view.fxml",
                "dev-environment-view.fxml",
                "file-analysis-view.fxml",
                "base64-view.fxml",
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

    @Test
    void newSystemToolsExposeBoundedOnDemandActions() throws IOException {
        String processView = readFxml("process-port-view.fxml");
        assertTrue(processView.contains("onAction=\"#handleRefresh\""));
        assertTrue(processView.contains("onAction=\"#handleTerminateSelected\""));
        assertTrue(processView.contains("fx:id=\"processTable\""));
        assertTrue(processView.contains("fx:id=\"portTable\""));

        String environmentView = readFxml("dev-environment-view.fxml");
        assertTrue(environmentView.contains("onAction=\"#handleInspect\""));
        assertTrue(environmentView.contains("fx:id=\"resultTable\""));

        String fileView = readFxml("file-analysis-view.fxml");
        assertTrue(fileView.contains("onAction=\"#handleSelectFile\""));
        assertTrue(fileView.contains("fx:id=\"sha256Field\""));
        assertTrue(fileView.contains("fx:id=\"lockStatusLabel\""));
        assertTrue(fileView.contains("fx:id=\"copySha256Button\""));
        assertTrue(fileView.contains("fx:id=\"copySha1Button\""));
        assertTrue(fileView.contains("fx:id=\"copyMd5Button\""));

        String base64View = readFxml("base64-view.fxml");
        assertTrue(base64View.contains("onAction=\"#handleEncode\""));
        assertTrue(base64View.contains("onAction=\"#handleDecode\""));
        assertTrue(base64View.contains("fx:id=\"variantComboBox\""));
        assertTrue(base64View.contains("fx:id=\"encodingComboBox\""));
    }

    @Test
    void networkQualityProvidesDirectAndProxyMultiProtocolMonitoring() throws IOException {
        String fxml = readFxml("network-quality-view.fxml");

        assertTrue(fxml.contains("fx:id=\"targetTable\""));
        assertTrue(fxml.contains("fx:id=\"rttChart\""));
        assertTrue(fxml.contains("fx:id=\"routePlanComboBox\""));
        assertTrue(fxml.contains("fx:id=\"proxyTypeComboBox\""));
        assertTrue(fxml.contains("fx:id=\"proxyPasswordField\""));
        assertTrue(fxml.contains("fx:id=\"targetEndpointField\""));
        assertTrue(fxml.contains("fx:id=\"systemTrendButton\""));
        assertTrue(fxml.contains("fx:id=\"proxyTrendButton\""));
        assertTrue(fxml.contains("fx:id=\"selectedQualityLabel\""));
        assertTrue(fxml.contains("fx:id=\"systemStabilityMetaLabel\""));
        assertTrue(fxml.contains("fx:id=\"proxyStabilityMetaLabel\""));
        assertTrue(fxml.contains("fx:id=\"copyReportButton\""));
        assertTrue(fxml.contains("text=\"代理与探测设置\""));
        assertTrue(fxml.contains("http://127.0.0.1:10808"));
        assertTrue(fxml.contains("createSymbols=\"true\""));
        assertTrue(fxml.contains("label=\"时间（秒）\""));
        assertTrue(fxml.contains("onAction=\"#handleStart\""));
        assertTrue(fxml.contains("onAction=\"#handleStop\""));
        assertTrue(fxml.contains("onAction=\"#handleReset\""));
        assertTrue(fxml.contains("onAction=\"#handleAddTarget\""));
        assertTrue(fxml.contains("onAction=\"#handleCopyReport\""));
        assertTrue(fxml.contains("服务可用性、响应时间与路由对比"));
        assertTrue(fxml.contains("最近 60 个样本"));
    }

    @Test
    void appLauncherExposesConfigurableBatchLaunchInterval() throws IOException {
        String fxml = readFxml("app-launcher-view.fxml");

        assertTrue(fxml.contains("fx:id=\"launchIntervalComboBox\""));
        assertTrue(fxml.contains("fx:id=\"cancelBatchLaunchButton\""));
        assertTrue(fxml.contains("text=\"启动间隔\""));
        assertTrue(fxml.contains("onAction=\"#handleLaunchAll\""));
        assertTrue(fxml.contains("onAction=\"#handleCancelBatchLaunch\""));
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
