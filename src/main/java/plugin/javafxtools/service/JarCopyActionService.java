package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.model.ProjectConfig;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * JAR 启动器文件复制动作编排。
 *
 * @author wwj
 */
public class JarCopyActionService {
    private final ExecutorService backgroundExecutor;
    private final JarFileService jarFileService;
    private final Runnable beforeCopy;
    private final Runnable afterCopy;
    private final Consumer<String> logger;
    private final Consumer<String> errorReporter;
    private final BiConsumer<ProjectConfig, Integer> buttonStateUpdater;
    private final ToIntFunction<ProjectConfig> statusPortResolver;

    /**
     * 创建复制动作服务。
     *
     * @param backgroundExecutor 后台执行器
     * @param jarFileService 文件复制服务
     * @param beforeCopy 复制前 UI 操作
     * @param afterCopy 复制后 UI 操作
     * @param logger 日志输出回调
     * @param errorReporter 错误提示回调
     * @param buttonStateUpdater 按钮状态刷新回调
     * @param statusPortResolver 项目状态端口读取回调
     */
    public JarCopyActionService(ExecutorService backgroundExecutor,
                                JarFileService jarFileService,
                                Runnable beforeCopy,
                                Runnable afterCopy,
                                Consumer<String> logger,
                                Consumer<String> errorReporter,
                                BiConsumer<ProjectConfig, Integer> buttonStateUpdater,
                                ToIntFunction<ProjectConfig> statusPortResolver) {
        this.backgroundExecutor = backgroundExecutor;
        this.jarFileService = jarFileService;
        this.beforeCopy = beforeCopy;
        this.afterCopy = afterCopy;
        this.logger = logger;
        this.errorReporter = errorReporter;
        this.buttonStateUpdater = buttonStateUpdater;
        this.statusPortResolver = statusPortResolver;
    }

    /**
     * 复制项目 JAR 和可选 lib 目录。
     *
     * @param project 项目配置快照
     */
    public void copyProjectFiles(ProjectConfig project) {
        backgroundExecutor.submit(() -> {
            Platform.runLater(beforeCopy);
            logger.accept("开始执行文件操作...");

            try {
                jarFileService.copyProjectFiles(project);
                Platform.runLater(() -> {
                    afterCopy.run();
                    logger.accept("文件操作完成");
                    buttonStateUpdater.accept(project, statusPortResolver.applyAsInt(project));
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    afterCopy.run();
                    errorReporter.accept("文件操作失败: " + e.getMessage());
                });
            }
        });
    }
}
