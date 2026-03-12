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

public class ToolsApplication extends Application {
    private static final String MAIN_VIEW_PATH = "/plugin/javafxtools/main-view.fxml";
    private static final String ICON_PATH = "/favicon.png";
    private static final String GLOBAL_STYLE_PATH = "/css/styles.css";

    private MainController mainController;

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
            Scene scene = new Scene(root, 1000, 700);
            URL styleUrl = getClass().getResource(GLOBAL_STYLE_PATH);
            if (styleUrl != null) {
                scene.getStylesheets().add(styleUrl.toExternalForm());
            }
            primaryStage.setScene(scene);
            primaryStage.setResizable(false);

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
            System.err.println("应用程序启动失败: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("启动失败", e);
        }
    }

    private void cleanupResources() {
        if (mainController != null) {
            mainController.cleanup();
        }
        // 可以添加其他全局资源清理逻辑
        System.out.println("应用程序资源已清理");
    }

    private URL requireResource(String path, String resourceName) {
        URL resource = getClass().getResource(path);
        if (Objects.isNull(resource)) {
            throw new IllegalStateException("无法找到" + resourceName + "文件，请确认资源路径: " + path);
        }
        return resource;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
