package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.service.LoggingService;
import plugin.javafxtools.util.TimeUtils;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主控制器 - 协调各功能模块和共享服务
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
    private KeepAliveManagerController keepAliveTabController;
    @FXML
    private MemoReminderController memoReminderTabController;

    // 共享服务
    private final LoggingService loggingService = new LoggingService();

    // 中央日志区域（可选）
    @FXML
    private TextArea centralLogArea;

    public AppLauncherController getAppLauncherController() {
        return appLauncherTabController;
    }

    /**
     * 初始化方法 - 由JavaFX在FXML加载完成后自动调用
     */
    @FXML
    public void initialize() {
        ensureUserDataDirectoryExists();
        try {
            if (centralLogArea != null) {
                loggingService.addGlobalLogArea(centralLogArea);
            }
            setupControllers();
            loggingService.info("主控制器初始化完成");
        } catch (Exception e) {
            System.err.println("主控制器初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void ensureUserDataDirectoryExists() {
        try {
            File userDataDir = new File("userData");
            if (!userDataDir.exists() && !userDataDir.mkdirs()) {
                loggingService.info("警告: 无法创建userData目录");
            }
            if (userDataDir.exists() && !userDataDir.isDirectory()) {
                loggingService.info("错误: userData存在但不是一个目录");
            }
            loggingService.info("已检查/创建userData目录");
        } catch (Exception e) {
            loggingService.info("检查/创建userData目录时出错: " + e.getMessage());
        }
    }

    /**
     * 配置各子控制器的日志服务并绑定日志区域
     */
    private void setupControllers() {
        checkControllerInjection();

        Map<String, Object> controllers = new LinkedHashMap<>();
        controllers.put("HTTP请求", httpRequestTabController);
        controllers.put("WebSocket", webSocketTabController);
        controllers.put("网络工具", networkToolsTabController);
        controllers.put("数据格式化", dataFormatTabController);
        controllers.put("字符串工具", strDataFormatTabController);
        controllers.put("启动项", appLauncherTabController);
        controllers.put("域名保活", keepAliveTabController);
        controllers.put("备忘提醒", memoReminderTabController);

        controllers.forEach((name, controller) -> loggingService.info(
                controller != null
                        ? String.format("%s控制器初始化成功", name)
                        : String.format("%s控制器注入为空", name)));
    }

    /**
     * 检查控制器注入状态
     */
    private void checkControllerInjection() {
        StringBuilder errorMsg = new StringBuilder();

        if (httpRequestTabController == null) {
            errorMsg.append("HTTP请求控制器注入失败\n");
        }
        if (webSocketTabController == null) {
            errorMsg.append("WebSocket控制器注入失败\n");
        }
        if (networkToolsTabController == null) {
            errorMsg.append("网络工具控制器注入失败\n");
        }
        if (dataFormatTabController == null) {
            errorMsg.append("数据格式化控制器注入失败\n");
        }
        if (strDataFormatTabController == null) {
            errorMsg.append("字符串控制器注入失败\n");
        }
        if (keepAliveTabController == null) {
            errorMsg.append("域名保活注入失败\n");
        }
        if (appLauncherTabController == null) {
            errorMsg.append("启动项控制器注入失败\n");
        }
        if (memoReminderTabController == null) {
            errorMsg.append("备忘提醒控制器注入失败\n");
        }

        if (!errorMsg.isEmpty()) {
            throw new IllegalStateException(errorMsg.toString());
        }
    }

    public TabPane getTabPane() {
        return tabPane;
    }

    /**
     * 根据 Tab 对象获取对应的日志区域
     */
    private TextArea getLogAreaByTab(Tab tab) {
        if (tab.getContent() != null && tab.getContent().getUserData() != null) {
            Object controller = tab.getContent().getUserData();
            if (controller instanceof ModuleLogger moduleLogger) {
                return moduleLogger.getLogArea();
            }
        }
        return null;
    }

    /**
     * 记录全局日志到中央日志区
     */
    private void logToGlobal(String level, String message) {
        String formattedMessage = String.format("[%s][%s] %s",
                TimeUtils.getCurrentDateTime(), level, message);

        Platform.runLater(() -> {
            if (centralLogArea != null && centralLogArea.getScene() != null) {
                centralLogArea.appendText(formattedMessage + "\n");
                centralLogArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    /**
     * 清理所有资源
     */
    public void cleanup() {
        try {
            if (httpRequestTabController != null) {
                httpRequestTabController.cleanup();
            }
            if (webSocketTabController != null) {
                webSocketTabController.cleanup();
            }
            if (networkToolsTabController != null) {
                networkToolsTabController.cleanup();
            }
            if (dataFormatTabController != null) {
                dataFormatTabController.cleanup();
            }
            if (strDataFormatTabController != null) {
                strDataFormatTabController.cleanup();
            }
            if (keepAliveTabController != null) {
                keepAliveTabController.cleanup();
            }
            if (appLauncherTabController != null) {
                appLauncherTabController.cleanup();
            }
            if (memoReminderTabController != null) {
                memoReminderTabController.cleanup();
            }
            if (centralLogArea != null) {
                centralLogArea.clear();
            }
            logToGlobal("INFO", "应用程序资源已清理");
            System.out.println("MainController 资源已清理");
        } catch (Exception e) {
            System.err.println("资源清理出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
