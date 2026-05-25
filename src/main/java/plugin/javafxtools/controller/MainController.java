package plugin.javafxtools.controller;

import javafx.fxml.FXML;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import plugin.javafxtools.service.LoggingService;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主控制器 - 协调各功能模块和共享服务
 */
public class MainController {
    /**
     * 主界面页签容器。
     */
    @FXML
    private TabPane tabPane;

    /**
     * HTTP 请求页签控制器。
     */
    @FXML
    private HttpRequestController httpRequestTabController;

    /**
     * WebSocket 页签控制器。
     */
    @FXML
    private WebSocketController webSocketTabController;

    /**
     * 网络工具页签控制器。
     */
    @FXML
    private NetworkToolsController networkToolsTabController;

    /**
     * 数据格式化页签控制器。
     */
    @FXML
    private DataFormatController dataFormatTabController;

    /**
     * 字符串工具页签控制器。
     */
    @FXML
    private StrDataFormatController strDataFormatTabController;

    /**
     * 启动项页签控制器。
     */
    @FXML
    private AppLauncherController appLauncherTabController;

    /**
     * JAR 应用启动器页签控制器。
     */
    @FXML
    private JarLauncherController jarLauncherTabController;

    /**
     * 域名保活页签控制器。
     */
    @FXML
    private KeepAliveManagerController keepAliveTabController;

    /**
     * 备忘提醒页签控制器。
     */
    @FXML
    private MemoReminderController memoReminderTabController;

    /**
     * 主界面共享的全局日志服务。
     */
    private final LoggingService loggingService = new LoggingService();

    /**
     * 主界面中央日志区域。
     */
    @FXML
    private TextArea centralLogArea;

    /**
     * 获取启动项页签控制器。
     *
     * @return 启动项控制器
     */
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
            loggingService.error("主控制器初始化失败: " + e.getMessage());
        }
    }

    /**
     * 确保用户数据目录存在。
     */
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
        controllers.put("JAR启动器", jarLauncherTabController);
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
        if (jarLauncherTabController == null) {
            errorMsg.append("JAR启动器控制器注入失败\n");
        }
        if (memoReminderTabController == null) {
            errorMsg.append("备忘提醒控制器注入失败\n");
        }

        if (!errorMsg.isEmpty()) {
            throw new IllegalStateException(errorMsg.toString());
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
            if (jarLauncherTabController != null) {
                jarLauncherTabController.cleanup();
            }
            if (memoReminderTabController != null) {
                memoReminderTabController.cleanup();
            }
            if (centralLogArea != null) {
                centralLogArea.clear();
            }
            loggingService.info("应用程序资源已清理");
        } catch (Exception e) {
            loggingService.error("资源清理出错: " + e.getMessage());
        }
    }
}
