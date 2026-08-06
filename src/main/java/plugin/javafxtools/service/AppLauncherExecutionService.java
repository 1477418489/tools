package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.model.AppProcessStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * 启动项工具的启动、停止和批量进程操作。
 *
 * @author wwj
 */
public class AppLauncherExecutionService {
    private static final long PROCESS_CHECK_DELAY_MS = 800;
    private static final long BATCH_LAUNCH_GAP_MS = 400;

    private final ModuleLogger logger;
    private final AppProcessManager processManager;
    private final AppLauncherStatusService statusService;
    private final ExecutorService backgroundExecutor;
    private final Map<String, AppProcessStatus> processStatusCache;

    /**
     * 创建启动项执行服务。
     *
     * @param logger 日志输出接口
     * @param processManager 进程管理器
     * @param statusService 状态检查服务
     * @param backgroundExecutor 后台执行器
     * @param processStatusCache 状态缓存
     */
    public AppLauncherExecutionService(ModuleLogger logger,
                                       AppProcessManager processManager,
                                       AppLauncherStatusService statusService,
                                       ExecutorService backgroundExecutor,
                                       Map<String, AppProcessStatus> processStatusCache) {
        this.logger = logger;
        this.processManager = processManager;
        this.statusService = statusService;
        this.backgroundExecutor = backgroundExecutor;
        this.processStatusCache = processStatusCache;
    }

    /**
     * 异步启动单个应用。
     *
     * @param appInfo 应用配置
     * @param onFinished 完成回调
     */
    public void launchSingle(AppInfo appInfo, Runnable onFinished) {
        try {
            backgroundExecutor.submit(() -> {
                try {
                    if (!restartApplication(appInfo)) {
                        return;
                    }
                } catch (RuntimeException e) {
                    logger.error("启动失败: " + appInfo.getAppPath() + " - " + e.getMessage());
                } finally {
                    notifyFinished(onFinished);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("应用启动任务已被拒绝");
            notifyFinished(onFinished);
        }
    }

    /**
     * 异步批量启动应用。
     *
     * @param appsToLaunch 应用快照
     * @param onFinished 完成回调
     */
    public void launchAll(List<AppInfo> appsToLaunch, Runnable onFinished) {
        logger.info("开始批量启动 " + appsToLaunch.size() + " 个应用程序...");

        try {
            backgroundExecutor.submit(() -> {
                try {
                    int successCount = 0;
                    int totalCount = appsToLaunch.size();
                    List<AppInfo> launchedApps = new ArrayList<>();
                    Map<String, Boolean> initialStates;
                    try {
                        initialStates = processManager.captureRunningStates(appsToLaunch);
                    } catch (IOException e) {
                        logger.error("无法确认现有进程状态，已取消批量启动: " + e.getMessage());
                        return;
                    }

                    for (int i = 0; i < totalCount; i++) {
                        AppInfo appInfo = appsToLaunch.get(i);
                        int currentIndex = i + 1;
                        logger.info(String.format("启动进度 %d/%d: %s",
                                currentIndex, totalCount, appInfo.getAppPath()));
                        try {
                            Boolean knownRunning = initialStates.get(appInfo.getAppPath());
                            if (!restartApplication(appInfo, knownRunning)) {
                                if (Thread.currentThread().isInterrupted()) {
                                    logger.info("批量启动已停止");
                                    break;
                                }
                                continue;
                            }
                            successCount++;
                            launchedApps.add(appInfo);
                            if (currentIndex < totalCount && !sleep(BATCH_LAUNCH_GAP_MS)) {
                                logger.info("批量启动已停止");
                                break;
                            }
                        } catch (RuntimeException e) {
                            logger.error("启动失败: " + appInfo.getAppPath() + " - " + e.getMessage());
                        }
                    }

                    int finalSuccessCount = successCount;
                    logger.info(String.format("批量启动完成: 成功 %d/%d", finalSuccessCount, totalCount));
                    if (!Thread.currentThread().isInterrupted() && !launchedApps.isEmpty()) {
                        statusService.verifyBatchLaunchStatus(launchedApps);
                    }
                } finally {
                    notifyFinished(onFinished);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("批量启动任务已被拒绝");
            notifyFinished(onFinished);
        }
    }

    /**
     * 异步终止单个应用进程。
     *
     * @param appInfo 应用配置
     * @param onFinished 终止完成回调
     */
    public void killProcess(AppInfo appInfo, Consumer<Boolean> onFinished) {
        String checkName = AppLauncherStatusService.resolveCheckName(appInfo);
        logger.info("正在尝试结束进程: " + checkName + " (" + appInfo.getAppPath() + ")");
        try {
            backgroundExecutor.submit(() -> {
                boolean killed = processManager.killProcess(appInfo.getAppPath(), checkName);
                Platform.runLater(() -> onFinished.accept(killed));
            });
        } catch (RejectedExecutionException e) {
            logger.error("进程终止任务已被拒绝");
            Platform.runLater(() -> onFinished.accept(false));
        }
    }

    /**
     * 异步终止应用集合中的进程。
     *
     * @param appsToKill 应用快照
     */
    public void killAll(List<AppInfo> appsToKill) {
        try {
            backgroundExecutor.submit(() -> {
                boolean anyProcessKilled = appsToKill.stream()
                        .map(appInfo -> processManager.killProcess(appInfo.getAppPath(),
                                AppLauncherStatusService.resolveCheckName(appInfo)))
                        .reduce(false, Boolean::logicalOr);

                if (!anyProcessKilled) {
                    logger.info("没有正在运行的进程");
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("批量终止任务已被拒绝");
        }
    }

    /**
     * 重启应用程序。
     *
     * @param appInfo 应用配置
     */
    private boolean restartApplication(AppInfo appInfo) {
        return restartApplication(appInfo, null);
    }

    private boolean restartApplication(AppInfo appInfo, Boolean knownRunning) {
        String path = appInfo.getAppPath();
        String checkName = AppLauncherStatusService.resolveCheckName(appInfo);
        boolean running;
        try {
            running = knownRunning != null
                    ? knownRunning
                    : processManager.isProcessRunning(path, checkName);
        } catch (IOException e) {
            logger.error("无法确认进程状态，已取消启动: " + path + " - " + e.getMessage());
            return false;
        }
        if (running) {
            logger.info("正在停止运行中的进程: " + path);
            if (processManager.killProcess(path, checkName)) {
                logger.info("成功停止进程: " + path);
                if (!sleep(PROCESS_CHECK_DELAY_MS)) {
                    return false;
                }
            } else {
                logger.error("停止进程失败: " + path);
                return false;
            }
        }
        return launchApplication(appInfo);
    }

    private boolean launchApplication(AppInfo appInfo) {
        String path = appInfo.getAppPath();
        String checkName = AppLauncherStatusService.resolveCheckName(appInfo);
        try {
            Process process = processManager.startProcess(path, checkName);
            if (process != null) {
                logger.info("成功启动: " + path);
                return true;
            }
        } catch (IOException e) {
            logger.error("启动失败: " + path + " - " + e.getMessage());
        }
        return false;
    }

    private boolean sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void notifyFinished(Runnable onFinished) {
        Platform.runLater(onFinished);
    }
}
