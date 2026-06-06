package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.model.ProjectConfig;

import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * JAR 启动器的启动、停止和端口等待编排。
 *
 * @author wwj
 */
public class JarLaunchService {
    private static final int PORT_READY_CHECK_ATTEMPTS = 60;
    private static final int PORT_READY_CHECK_DELAY_MS = 500;

    private final ExecutorService backgroundExecutor;
    private final JarPortProcessService portProcessService;
    private final JarFileService jarFileService;
    private final Consumer<String> logger;
    private final Consumer<String> errorReporter;
    private final IntPredicate confirmKillProcessOnPort;
    private final BiConsumer<ProjectConfig, Integer> updateButtonStates;
    private final BiConsumer<ProjectConfig, Integer> runningPortRecorder;
    private final Consumer<ProjectConfig> runningPortClearer;
    private final Consumer<Boolean> launchButtonDisabler;
    private final Consumer<Boolean> stopButtonDisabler;

    /**
     * 创建 JAR 启停编排服务。
     *
     * @param backgroundExecutor 后台执行器
     * @param portProcessService 端口进程服务
     * @param jarFileService JAR 文件服务
     * @param logger 日志输出回调
     * @param errorReporter 错误提示回调
     * @param confirmKillProcessOnPort 端口冲突确认回调
     * @param updateButtonStates 按钮状态刷新回调
     * @param runningPortRecorder 运行端口写入回调
     * @param runningPortClearer 运行端口清除回调
     * @param launchButtonDisabler 启动按钮禁用回调
     * @param stopButtonDisabler 停止按钮禁用回调
     */
    public JarLaunchService(ExecutorService backgroundExecutor,
                            JarPortProcessService portProcessService,
                            JarFileService jarFileService,
                            Consumer<String> logger,
                            Consumer<String> errorReporter,
                            IntPredicate confirmKillProcessOnPort,
                            BiConsumer<ProjectConfig, Integer> updateButtonStates,
                            BiConsumer<ProjectConfig, Integer> runningPortRecorder,
                            Consumer<ProjectConfig> runningPortClearer,
                            Consumer<Boolean> launchButtonDisabler,
                            Consumer<Boolean> stopButtonDisabler) {
        this.backgroundExecutor = backgroundExecutor;
        this.portProcessService = portProcessService;
        this.jarFileService = jarFileService;
        this.logger = logger;
        this.errorReporter = errorReporter;
        this.confirmKillProcessOnPort = confirmKillProcessOnPort;
        this.updateButtonStates = updateButtonStates;
        this.runningPortRecorder = runningPortRecorder;
        this.runningPortClearer = runningPortClearer;
        this.launchButtonDisabler = launchButtonDisabler;
        this.stopButtonDisabler = stopButtonDisabler;
    }

    /**
     * 启动项目。
     *
     * @param project 项目配置
     * @param port 启动端口
     * @param profile Spring profile
     */
    public void launch(ProjectConfig project, int port, String profile) {
        launchButtonDisabler.accept(true);
        backgroundExecutor.submit(() -> {
            if (!portProcessService.checkPortInUse(port)) {
                startLaunchProcess(project, port, profile);
                return;
            }

            Platform.runLater(() -> {
                if (!confirmKillProcessOnPort.test(port)) {
                    launchButtonDisabler.accept(false);
                    return;
                }

                backgroundExecutor.submit(() -> {
                    boolean portFreed = portProcessService.killProcessOnPort(port);
                    if (!portFreed && portProcessService.checkPortInUse(port)) {
                        Platform.runLater(() -> {
                            launchButtonDisabler.accept(false);
                            errorReporter.accept("端口 " + port + " 未释放，已取消启动");
                        });
                        return;
                    }
                    startLaunchProcess(project, port, profile);
                });
            });
        });
    }

    /**
     * 停止项目。
     *
     * @param project 项目配置
     * @param port 停止端口
     */
    public void stop(ProjectConfig project, int port) {
        stopButtonDisabler.accept(true);
        backgroundExecutor.submit(() -> {
            if (!portProcessService.checkPortInUse(port)) {
                runningPortClearer.accept(project);
                Platform.runLater(() -> updateButtonStates.accept(project, port));
                return;
            }

            Platform.runLater(() -> {
                if (!confirmKillProcessOnPort.test(port)) {
                    updateButtonStates.accept(project, port);
                    return;
                }

                backgroundExecutor.submit(() -> {
                    boolean stopped = portProcessService.killProcessOnPort(port);
                    boolean stillRunning = portProcessService.checkPortInUse(port);
                    if (!stillRunning) {
                        runningPortClearer.accept(project);
                        if (!stopped) {
                            logger.accept("端口 " + port + " 已释放");
                        }
                    } else {
                        logger.accept("端口 " + port + " 仍被占用，请检查是否有守护进程自动重启");
                    }
                    Platform.runLater(() -> updateButtonStates.accept(project, port));
                });
            });
        });
    }

    private void startLaunchProcess(ProjectConfig project, int port, String profile) {
        backgroundExecutor.submit(() -> {
            try {
                Process process = jarFileService.startJavaApplication(project, port, profile);
                Platform.runLater(() -> {
                    logger.accept("应用程序已作为独立进程启动");
                    logger.accept("进程ID: " + process.pid());
                    logger.accept("即使关闭此工具，应用程序也将继续运行");
                    runningPortRecorder.accept(project, port);
                    launchButtonDisabler.accept(true);
                    stopButtonDisabler.accept(false);
                });

                boolean portReady = waitForPortReady(port);
                Platform.runLater(() -> {
                    if (portReady) {
                        logger.accept("端口 " + port + " 已就绪");
                    } else {
                        logger.accept("等待端口就绪超时，应用可能仍在启动中");
                    }
                    updateButtonStates.accept(project, port);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    launchButtonDisabler.accept(false);
                    errorReporter.accept("启动失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
                });
            }
        });
    }

    private boolean waitForPortReady(int port) throws InterruptedException {
        for (int i = 0; i < PORT_READY_CHECK_ATTEMPTS; i++) {
            Thread.sleep(PORT_READY_CHECK_DELAY_MS);
            if (portProcessService.checkPortInUse(port)) {
                return true;
            }
        }
        return false;
    }
}
