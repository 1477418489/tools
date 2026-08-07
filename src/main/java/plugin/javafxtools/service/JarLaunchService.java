package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.model.ProjectConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
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
     */
    public JarLaunchService(ExecutorService backgroundExecutor,
                            JarPortProcessService portProcessService,
                            JarFileService jarFileService,
                            Consumer<String> logger,
                            Consumer<String> errorReporter,
                            IntPredicate confirmKillProcessOnPort,
                            BiConsumer<ProjectConfig, Integer> updateButtonStates,
                            BiConsumer<ProjectConfig, Integer> runningPortRecorder,
                            Consumer<ProjectConfig> runningPortClearer) {
        this.backgroundExecutor = backgroundExecutor;
        this.portProcessService = portProcessService;
        this.jarFileService = jarFileService;
        this.logger = logger;
        this.errorReporter = errorReporter;
        this.confirmKillProcessOnPort = confirmKillProcessOnPort;
        this.updateButtonStates = updateButtonStates;
        this.runningPortRecorder = runningPortRecorder;
        this.runningPortClearer = runningPortClearer;
    }

    /**
     * 启动项目。
     *
     * @param project 项目配置
     * @param port 启动端口
     * @param profile Spring profile
     */
    public void launch(ProjectConfig project, int port, String profile) {
        try {
            backgroundExecutor.submit(() -> {
                try {
                    JarPortProcessService.ProjectPortInspection inspection =
                            portProcessService.inspectProjectPort(project, port);
                    switch (inspection.state()) {
                        case FREE -> startLaunchProcess(project, port, profile);
                        case PROJECT_RUNNING -> Platform.runLater(
                                () -> confirmPortReleaseAndLaunch(project, port, profile));
                        case OCCUPIED -> finishWithError(project, port,
                                "端口 " + port + " 被其他进程占用，已取消启动");
                    }
                } catch (RuntimeException e) {
                    finishWithError(project, port, "启动检查失败: " + errorMessage(e));
                }
            });
        } catch (RejectedExecutionException e) {
            finishWithError(project, port, "启动服务已关闭");
        }
    }

    private void confirmPortReleaseAndLaunch(ProjectConfig project, int port, String profile) {
        try {
            if (!confirmKillProcessOnPort.test(port)) {
                updateButtonStates.accept(project, port);
                return;
            }
        } catch (RuntimeException e) {
            finishWithError(project, port, "端口确认失败: " + errorMessage(e));
            return;
        }

        try {
            backgroundExecutor.submit(() -> {
                try {
                    boolean projectStopped = portProcessService.killProjectOnPort(project, port);
                    JarPortProcessService.ProjectPortState state =
                            portProcessService.inspectProjectPort(project, port).state();
                    if (!projectStopped || state != JarPortProcessService.ProjectPortState.FREE) {
                        finishWithError(project, port, "端口 " + port + " 未释放，已取消启动");
                        return;
                    }
                    startLaunchProcess(project, port, profile);
                } catch (RuntimeException e) {
                    finishWithError(project, port, "释放端口失败: " + errorMessage(e));
                }
            });
        } catch (RejectedExecutionException e) {
            finishWithError(project, port, "启动服务已关闭");
        }
    }

    /**
     * 停止项目。
     *
     * @param project 项目配置
     * @param port 停止端口
     */
    public void stop(ProjectConfig project, int port) {
        try {
            backgroundExecutor.submit(() -> {
                try {
                    JarPortProcessService.ProjectPortInspection inspection =
                            portProcessService.inspectProjectPort(project, port);
                    switch (inspection.state()) {
                        case FREE -> {
                            runningPortClearer.accept(project);
                            Platform.runLater(() -> updateButtonStates.accept(project, port));
                        }
                        case PROJECT_RUNNING -> Platform.runLater(() -> confirmStop(project, port));
                        case OCCUPIED -> finishWithError(project, port,
                                "端口 " + port + " 被其他进程占用，未执行停止操作");
                    }
                } catch (RuntimeException e) {
                    finishWithError(project, port, "停止检查失败: " + errorMessage(e));
                }
            });
        } catch (RejectedExecutionException e) {
            finishWithError(project, port, "停止服务已关闭");
        }
    }

    private void confirmStop(ProjectConfig project, int port) {
        try {
            if (!confirmKillProcessOnPort.test(port)) {
                updateButtonStates.accept(project, port);
                return;
            }
        } catch (RuntimeException e) {
            finishWithError(project, port, "停止确认失败: " + errorMessage(e));
            return;
        }

        try {
            backgroundExecutor.submit(() -> {
                try {
                    boolean stopped = portProcessService.killProjectOnPort(project, port);
                    JarPortProcessService.ProjectPortState state =
                            portProcessService.inspectProjectPort(project, port).state();
                    if (state != JarPortProcessService.ProjectPortState.PROJECT_RUNNING) {
                        runningPortClearer.accept(project);
                        if (!stopped) {
                            logger.accept("项目进程已不再监听端口 " + port);
                        }
                    } else {
                        logger.accept("项目仍在监听端口 " + port + "，请检查是否有守护进程自动重启");
                    }
                    Platform.runLater(() -> updateButtonStates.accept(project, port));
                } catch (RuntimeException e) {
                    finishWithError(project, port, "停止失败: " + errorMessage(e));
                }
            });
        } catch (RejectedExecutionException e) {
            finishWithError(project, port, "停止服务已关闭");
        }
    }

    private void startLaunchProcess(ProjectConfig project, int port, String profile) {
        try {
            Process process = jarFileService.startJavaApplication(project, port, profile);
            Platform.runLater(() -> {
                logger.accept("应用程序已作为独立进程启动");
                logger.accept("Java进程ID: " + process.pid());
                logger.accept("即使关闭此工具，应用程序也将继续运行");
                runningPortRecorder.accept(project, port);
            });

            boolean portReady = waitForPortReady(project, port, process);
            Platform.runLater(() -> {
                if (portReady) {
                    logger.accept("端口 " + port + " 已就绪");
                } else {
                    logger.accept("等待端口就绪超时，应用可能仍在启动中");
                }
                updateButtonStates.accept(project, port);
            });
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            finishWithError(project, port, "启动失败: " + errorMessage(e));
        }
    }

    private void finishWithError(ProjectConfig project, int port, String message) {
        Runnable finish = () -> {
            try {
                errorReporter.accept(message);
            } finally {
                updateButtonStates.accept(project, port);
            }
        };
        if (Platform.isFxApplicationThread()) {
            finish.run();
        } else {
            Platform.runLater(finish);
        }
    }

    private String errorMessage(Exception exception) {
        return exception.getMessage() != null ? exception.getMessage() : "未知错误";
    }

    private boolean waitForPortReady(ProjectConfig project, int port, Process process)
            throws InterruptedException {
        for (int i = 0; i < PORT_READY_CHECK_ATTEMPTS; i++) {
            Thread.sleep(PORT_READY_CHECK_DELAY_MS);
            if (!process.isAlive()) {
                logger.accept("Java进程已提前退出，退出码: " + process.exitValue());
                return false;
            }
            if (portProcessService.checkPortInUse(port)) {
                return portProcessService.inspectProjectPort(project, port).state()
                        == JarPortProcessService.ProjectPortState.PROJECT_RUNNING;
            }
        }
        return false;
    }
}
