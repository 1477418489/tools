package plugin.javafxtools;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import plugin.javafxtools.controller.MainController;

import java.net.URL;
import java.util.Objects;

/**
 * JavaFX 工具箱应用入口，负责加载主界面和全局资源。
 */
public class ToolsApplication extends Application {
    /**
     * 主界面 FXML 资源路径。
     */
    private static final String MAIN_VIEW_PATH = "/plugin/javafxtools/main-view.fxml";
    /**
     * 应用图标资源路径。
     */
    private static final String ICON_PATH = "/favicon.png";
    /**
     * 全局样式资源路径。
     */
    private static final String GLOBAL_STYLE_PATH = "/css/styles.css";

    /**
     * 主界面控制器引用，用于应用关闭时释放子模块资源。
     */
    private MainController mainController;

    /**
     * 初始化并展示 JavaFX 主舞台。
     *
     * @param primaryStage JavaFX 主舞台
     */
    @Override
    public void start(Stage primaryStage) {
        try {
            URL mainFxmlUrl = requireResource(MAIN_VIEW_PATH, "main-view.fxml");

            // 加载主界面FXML文件
            FXMLLoader loader = new FXMLLoader(mainFxmlUrl);
            Parent root = loader.load();
            // 获取主控制器实例
            mainController = loader.getController();
            // 配置主舞台
            primaryStage.setTitle("JavaFX工具集 v1.0");
            Scene scene = new Scene(root, 1180, 760);
            URL styleUrl = getClass().getResource(GLOBAL_STYLE_PATH);
            if (styleUrl != null) {
                scene.getStylesheets().add(styleUrl.toExternalForm());
            }
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(1024);
            primaryStage.setMinHeight(720);
            primaryStage.setResizable(true);

            // 如果主控制器包含AppLauncherController，设置primaryStage
            if (mainController != null && mainController.getAppLauncherController() != null) {
                mainController.getAppLauncherController().setPrimaryStage(primaryStage);
            }
            // 添加关闭事件处理
            primaryStage.setOnCloseRequest(_ -> {
                cleanupResources();
                System.exit(0);
            });
            // 加载并设置应用图标（支持PNG/ICO等格式）
            URL iconUrl = getClass().getResource(ICON_PATH);
            if (iconUrl != null) {
                primaryStage.getIcons().add(new Image(iconUrl.toExternalForm()));
            }
            primaryStage.show();

        } catch (Exception e) {
            throw new RuntimeException("启动失败", e);
        }
    }

    /**
     * 清理主控制器及其子模块占用的资源。
     */
    private void cleanupResources() {
        if (mainController != null) {
            mainController.cleanup();
        }
    }

    /**
     * 获取必需资源，资源缺失时抛出明确异常。
     *
     * @param path 资源路径
     * @param resourceName 资源名称
     * @return 资源 URL
     */
    private URL requireResource(String path, String resourceName) {
        URL resource = getClass().getResource(path);
        if (Objects.isNull(resource)) {
            throw new IllegalStateException("无法找到" + resourceName + "文件，请确认资源路径: " + path);
        }
        return resource;
    }

    /**
     * 应用程序命令行入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        launch(args);
    }
}
