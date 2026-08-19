package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.model.ProjectConfig;

import java.util.List;
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
    private static final int STARTUP_LOG_MAX_READ_BYTES = 64 * 1024;
    private static final int STARTUP_LOG_MAX_LINES = 100;

    private final ExecutorService backgroundExecutor;
    private final ExecutorService startupMonitorExecutor;
    private final JarPortProcessService portProcessService;
    private final JarFileService jarFileService;
    private final Consumer<String> logger;
    private final Consumer<String> errorReporter;
    private final IntPredicate confirmKillProcessOnPort;
    private final BiConsumer<ProjectConfig, Integer> operationFinished;
    private final BiConsumer<ProjectConfig, Integer> processStateChanged;
    private final BiConsumer<ProjectConfig, Integer> runningPortRecorder;
    private final Consumer<ProjectConfig> runningPortClearer;
    private final Consumer<Runnable> uiDispatcher;

    /**
     * 创建 JAR 启停编排服务。
     *
     * @param backgroundExecutor 后台执行器
     * @param startupMonitorExecutor 启动日志和端口就绪监控执行器
     * @param portProcessService 端口进程服务
     * @param jarFileService JAR 文件服务
     * @param logger 日志输出回调
     * @param errorReporter 错误提示回调
     * @param confirmKillProcessOnPort 端口冲突确认回调
     * @param operationFinished 当前启动或停止操作完成回调
     * @param processStateChanged 已启动进程后续退出时的状态刷新回调
     * @param runningPortRecorder 运行端口写入回调
     * @param runningPortClearer 运行端口清除回调
     */
    public JarLaunchService(ExecutorService backgroundExecutor,
                            ExecutorService startupMonitorExecutor,
                            JarPortProcessService portProcessService,
                            JarFileService jarFileService,
                            Consumer<String> logger,
                            Consumer<String> errorReporter,
                            IntPredicate confirmKillProcessOnPort,
                            BiConsumer<ProjectConfig, Integer> operationFinished,
                            BiConsumer<ProjectConfig, Integer> processStateChanged,
                            BiConsumer<ProjectConfig, Integer> runningPortRecorder,
                            Consumer<ProjectConfig> runningPortClearer) {
        this(backgroundExecutor, startupMonitorExecutor, portProcessService, jarFileService,
                logger, errorReporter, confirmKillProcessOnPort, operationFinished,
                processStateChanged,
                runningPortRecorder, runningPortClearer, JarLaunchService::dispatchOnFxThread);
    }

    JarLaunchService(ExecutorService backgroundExecutor,
                     ExecutorService startupMonitorExecutor,
                     JarPortProcessService portProcessService,
                     JarFileService jarFileService,
                     Consumer<String> logger,
                     Consumer<String> errorReporter,
                     IntPredicate confirmKillProcessOnPort,
                     BiConsumer<ProjectConfig, Integer> operationFinished,
                     BiConsumer<ProjectConfig, Integer> processStateChanged,
                     BiConsumer<ProjectConfig, Integer> runningPortRecorder,
                     Consumer<ProjectConfig> runningPortClearer,
                     Consumer<Runnable> uiDispatcher) {
        this.backgroundExecutor = backgroundExecutor;
        this.startupMonitorExecutor = startupMonitorExecutor;
        this.portProcessService = portProcessService;
        this.jarFileService = jarFileService;
        this.logger = logger;
        this.errorReporter = errorReporter;
        this.confirmKillProcessOnPort = confirmKillProcessOnPort;
        this.operationFinished = operationFinished;
        this.processStateChanged = processStateChanged;
        this.runningPortRecorder = runningPortRecorder;
        this.runningPortClearer = runningPortClearer;
        this.uiDispatcher = uiDispatcher;
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
                        case PROJECT_RUNNING -> uiDispatcher.accept(
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
                operationFinished.accept(project, port);
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
                            uiDispatcher.accept(() -> operationFinished.accept(project, port));
                        }
                        case PROJECT_RUNNING -> uiDispatcher.accept(() -> confirmStop(project, port));
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
                operationFinished.accept(project, port);
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
                    uiDispatcher.accept(() -> operationFinished.accept(project, port));
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
            JarFileService.JavaLaunch launch =
                    jarFileService.startJavaApplication(project, port, profile);
            Process process = launch.process();
            uiDispatcher.accept(() -> {
                logger.accept("[" + project.getName() + "] 应用程序已作为后台独立进程启动");
                logger.accept("Java进程ID: " + process.pid());
                logger.accept("本次启动日志将实时显示，并持续写入: " + launch.outputLog());
                logger.accept("即使关闭此工具，应用程序也将继续运行");
                runningPortRecorder.accept(project, port);
            });

            startStartupMonitor(project, port, launch);
        } catch (Exception e) {
            finishWithError(project, port, "启动失败: " + errorMessage(e));
        }
    }

    private void startStartupMonitor(ProjectConfig project,
                                     int port,
                                     JarFileService.JavaLaunch launch) {
        try {
            startupMonitorExecutor.execute(() -> monitorStartup(project, port, launch));
        } catch (RejectedExecutionException e) {
            logger.accept("[" + project.getName() + "] 启动监控服务已关闭，应用仍将继续运行");
            registerProcessExit(project, port, launch.process());
            uiDispatcher.accept(() -> operationFinished.accept(project, port));
        }
    }

    private void monitorStartup(ProjectConfig project,
                                int port,
                                JarFileService.JavaLaunch launch) {
        JarStartupLogTailer logTailer = new JarStartupLogTailer(
                launch.outputLog(), launch.outputStartOffset());
        StartupOutcome outcome;
        try {
            outcome = waitForPortReady(project, port, launch.process(), logTailer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitStartupLogs(project, logTailer, true);
            return;
        } catch (RuntimeException e) {
            emitStartupLogs(project, logTailer, true);
            logger.accept("[" + project.getName() + "] 启动状态监控失败: " + errorMessage(e));
            outcome = StartupOutcome.MONITOR_FAILED;
        }

        emitStartupLogs(project, logTailer, true);
        if (outcome != StartupOutcome.EXITED) {
            registerProcessExit(project, port, launch.process());
        }
        StartupOutcome finalOutcome = outcome;
        uiDispatcher.accept(() -> {
            switch (finalOutcome) {
                case READY -> logger.accept("[" + project.getName() + "] 端口 " + port + " 已就绪");
                case EXITED -> {
                    runningPortClearer.accept(project);
                    logger.accept("[" + project.getName() + "] Java进程启动期间已退出，退出码: "
                            + launch.process().exitValue());
                }
                case TIMED_OUT -> logger.accept("[" + project.getName()
                        + "] 等待端口就绪超时，进程仍在运行，可继续查看启动日志");
                case MONITOR_FAILED -> logger.accept("[" + project.getName()
                        + "] 无法确认端口就绪状态，进程仍在运行");
            }
            operationFinished.accept(project, port);
        });
        if (outcome != StartupOutcome.EXITED) {
            continueLogTail(project, launch.process(), logTailer);
        }
    }

    private void continueLogTail(ProjectConfig project,
                                  Process process,
                                  JarStartupLogTailer logTailer) {
        try {
            while (process.isAlive()) {
                Thread.sleep(PORT_READY_CHECK_DELAY_MS);
                emitStartupLogs(project, logTailer, false);
            }
            emitStartupLogs(project, logTailer, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitStartupLogs(project, logTailer, true);
        }
    }

    private void registerProcessExit(ProjectConfig project, int port, Process process) {
        process.onExit().thenRun(() -> {
            uiDispatcher.accept(() -> {
                runningPortClearer.accept(project);
                logger.accept("[" + project.getName() + "] Java进程已退出，退出码: "
                        + process.exitValue());
                processStateChanged.accept(project, port);
            });
        });
    }

    private void finishWithError(ProjectConfig project, int port, String message) {
        Runnable finish = () -> {
            try {
                errorReporter.accept(message);
            } finally {
                operationFinished.accept(project, port);
            }
        };
        uiDispatcher.accept(finish);
    }

    private String errorMessage(Exception exception) {
        return exception.getMessage() != null ? exception.getMessage() : "未知错误";
    }

    private StartupOutcome waitForPortReady(ProjectConfig project,
                                            int port,
                                            Process process,
                                            JarStartupLogTailer logTailer)
            throws InterruptedException {
        for (int i = 0; i < PORT_READY_CHECK_ATTEMPTS; i++) {
            Thread.sleep(PORT_READY_CHECK_DELAY_MS);
            emitStartupLogs(project, logTailer, false);
            if (!process.isAlive()) {
                return StartupOutcome.EXITED;
            }
            if (portProcessService.checkPortInUse(port)) {
                return portProcessService.inspectProjectPort(project, port).state()
                        == JarPortProcessService.ProjectPortState.PROJECT_RUNNING
                        ? StartupOutcome.READY : StartupOutcome.MONITOR_FAILED;
            }
        }
        return StartupOutcome.TIMED_OUT;
    }

    private void emitStartupLogs(ProjectConfig project,
                                 JarStartupLogTailer logTailer,
                                 boolean flushPartial) {
        try {
            List<String> lines = logTailer.readAvailable(
                    flushPartial, STARTUP_LOG_MAX_READ_BYTES, STARTUP_LOG_MAX_LINES);
            if (lines.isEmpty()) {
                return;
            }
            String prefix = "[" + project.getName() + "] ";
            logger.accept(lines.stream()
                    .map(line -> prefix + line)
                    .collect(java.util.stream.Collectors.joining("\n")));
        } catch (java.io.IOException e) {
            logger.accept("[" + project.getName() + "] 读取启动日志失败: " + e.getMessage());
        }
    }

    private enum StartupOutcome {
        READY,
        EXITED,
        TIMED_OUT,
        MONITOR_FAILED
    }

    private static void dispatchOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        try {
            Platform.runLater(action);
        } catch (IllegalStateException ignored) {
            // JavaFX 已关闭，无需再更新界面。
        }
    }
}
