package plugin.javafxtools.controller;

import javafx.fxml.FXML;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import plugin.javafxtools.service.LoggingService;
import plugin.javafxtools.util.AppDataPaths;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 主控制器，负责校验子控制器注入并协调应用级资源清理。
 */
public class MainController {
    @FXML
    private TabPane tabPane;
    @FXML
    private HttpRequestController httpRequestTabController;
    @FXML
    private WebSocketController webSocketTabController;
    @FXML
    private NetworkToolsController networkToolsTabController;
    @FXML
    private DataFormatController dataFormatTabController;
    @FXML
    private StrDataFormatController strDataFormatTabController;
    @FXML
    private AppLauncherController appLauncherTabController;
    @FXML
    private JarLauncherController jarLauncherTabController;
    @FXML
    private KeepAliveManagerController keepAliveTabController;
    @FXML
    private MemoReminderController memoReminderTabController;
    @FXML
    private TextArea centralLogArea;
    @FXML
    private TitledPane systemLogPane;

    private final LoggingService loggingService = new LoggingService();

    /**
     * 获取启动项页签控制器。
     *
     * @return 启动项控制器
     */
    public AppLauncherController getAppLauncherController() {
        return appLauncherTabController;
    }

    /**
     * 完成主界面及子控制器初始化。
     */
    @FXML
    public void initialize() {
        systemLogPane.setExpanded(false);
        ensureAppDataDirectoryExists();
        try {
            if (centralLogArea != null) {
                loggingService.addGlobalLogArea(centralLogArea);
            }
            setupControllers();
            loggingService.info("主控制器初始化完成");
        } catch (RuntimeException e) {
            loggingService.error("主控制器初始化失败: " + errorMessage(e));
            throw e;
        }
    }

    private void ensureAppDataDirectoryExists() {
        try {
            AppDataPaths.ensureDataDirectory();
        } catch (IOException | SecurityException e) {
            loggingService.error("无法创建用户数据目录: " + errorMessage(e));
        }
    }

    private void setupControllers() {
        List<ControllerBinding> controllers = controllerBindings();
        validateControllerInjection(controllers);
        controllers.forEach(binding -> loggingService.info(binding.name() + "控制器初始化成功"));
    }

    private List<ControllerBinding> controllerBindings() {
        return List.of(
                new ControllerBinding("HTTP请求", httpRequestTabController,
                        () -> httpRequestTabController.cleanup()),
                new ControllerBinding("WebSocket", webSocketTabController,
                        () -> webSocketTabController.cleanup()),
                new ControllerBinding("网络工具", networkToolsTabController,
                        () -> networkToolsTabController.cleanup()),
                new ControllerBinding("数据格式化", dataFormatTabController,
                        () -> dataFormatTabController.cleanup()),
                new ControllerBinding("字符串工具", strDataFormatTabController,
                        () -> strDataFormatTabController.cleanup()),
                new ControllerBinding("启动项", appLauncherTabController,
                        () -> appLauncherTabController.cleanup()),
                new ControllerBinding("JAR启动器", jarLauncherTabController,
                        () -> jarLauncherTabController.cleanup()),
                new ControllerBinding("域名保活", keepAliveTabController,
                        () -> keepAliveTabController.cleanup()),
                new ControllerBinding("备忘提醒", memoReminderTabController,
                        () -> memoReminderTabController.cleanup())
        );
    }

    private void validateControllerInjection(List<ControllerBinding> controllers) {
        String missingControllers = controllers.stream()
                .filter(binding -> binding.controller() == null)
                .map(ControllerBinding::name)
                .collect(Collectors.joining("、"));
        if (!missingControllers.isEmpty()) {
            throw new IllegalStateException("控制器注入失败: " + missingControllers);
        }
    }

    /**
     * 获取主界面页签容器。
     *
     * @return 页签容器
     */
    public TabPane getTabPane() {
        return tabPane;
    }

    /**
     * 逐项清理子模块。单个模块失败不会阻止其余模块释放资源。
     */
    public void cleanup() {
        List<String> failures = new ArrayList<>();
        controllerBindings().stream()
                .filter(binding -> binding.controller() != null)
                .forEach(binding -> cleanupController(binding.name(), binding.cleanupAction(), failures));
        if (centralLogArea != null) {
            centralLogArea.clear();
        }

        if (failures.isEmpty()) {
            loggingService.info("应用程序资源已清理");
        } else {
            loggingService.error("部分模块资源清理失败: " + String.join("、", failures));
        }
    }

    private void cleanupController(String name, Runnable cleanupAction, List<String> failures) {
        try {
            cleanupAction.run();
        } catch (RuntimeException e) {
            failures.add(name + " (" + errorMessage(e) + ")");
        }
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private record ControllerBinding(String name, Object controller, Runnable cleanupAction) {
    }
}
