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
import java.util.function.Consumer;

/**
 * 启动项工具的启动、停止和批量进程操作。
 *
 * @author wwj
 */
public class AppLauncherExecutionService {
    private static final long PROCESS_CHECK_DELAY_MS = 800;
    private static final long SINGLE_STATUS_CHECK_DELAY_MS = 1500;
    private static final long EXE_LAUNCH_DELAY_MS = 5000;
    private static final long SCRIPT_LAUNCH_DELAY_MS = 3000;
    private static final long DEFAULT_LAUNCH_DELAY_MS = 8000;

    private final ModuleLogger logger;
    private final AppProcessManager processManager;
    private final AppLauncherStatusService statusService;
    private final ExecutorService backgroundExecutor;
    private final Map<String, AppProcessStatus> processStatusCache;
    private final Runnable updateAppList;

    /**
     * 创建启动项执行服务。
     *
     * @param logger 日志输出接口
     * @param processManager 进程管理器
     * @param statusService 状态检查服务
     * @param backgroundExecutor 后台执行器
     * @param processStatusCache 状态缓存
     * @param updateAppList 刷新应用列表回调
     */
    public AppLauncherExecutionService(ModuleLogger logger,
                                       AppProcessManager processManager,
                                       AppLauncherStatusService statusService,
                                       ExecutorService backgroundExecutor,
                                       Map<String, AppProcessStatus> processStatusCache,
                                       Runnable updateAppList) {
        this.logger = logger;
        this.processManager = processManager;
        this.statusService = statusService;
        this.backgroundExecutor = backgroundExecutor;
        this.processStatusCache = processStatusCache;
        this.updateAppList = updateAppList;
    }

    /**
     * 异步启动单个应用。
     *
     * @param appInfo 应用配置
     */
    public void launchSingle(AppInfo appInfo) {
        backgroundExecutor.submit(() -> {
            restartApplication(appInfo);
            String checkName = AppLauncherStatusService.resolveCheckName(appInfo);
            processManager.clearProcessCache(checkName.toLowerCase());
            if (sleep(SINGLE_STATUS_CHECK_DELAY_MS)) {
                statusService.forceCheckSingleProcessStatus(appInfo);
            }
        });
    }

    /**
     * 异步批量启动应用。
     *
     * @param appsToLaunch 应用快照
     */
    public void launchAll(List<AppInfo> appsToLaunch) {
        logger.info("开始批量启动 " + appsToLaunch.size() + " 个应用程序...");
        processStatusCache.clear();
        processManager.clearAllProcessCache();

        backgroundExecutor.submit(() -> {
            int successCount = 0;
            int totalCount = appsToLaunch.size();
            List<AppInfo> launchedApps = new ArrayList<>();

            for (int i = 0; i < totalCount; i++) {
                AppInfo appInfo = appsToLaunch.get(i);
                int currentIndex = i + 1;
                logger.info(String.format("启动进度 %d/%d: %s",
                        currentIndex, totalCount, appInfo.getAppPath()));
                try {
                    restartApplication(appInfo);
                    successCount++;
                    launchedApps.add(appInfo);
                    if (!sleep(calculateLaunchDelay(appInfo.getAppPath()))) {
                        logger.error("批量启动被中断");
                        break;
                    }
                } catch (RuntimeException e) {
                    logger.error("启动失败: " + appInfo.getAppPath() + " - " + e.getMessage());
                }
            }

            int finalSuccessCount = successCount;
            Platform.runLater(() -> {
                logger.info(String.format("批量启动完成: 成功 %d/%d", finalSuccessCount, totalCount));
                backgroundExecutor.submit(() -> statusService.verifyBatchLaunchStatus(launchedApps));
            });
        });
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
        backgroundExecutor.submit(() -> {
            boolean killed = processManager.killProcess(checkName);
            Platform.runLater(() -> onFinished.accept(killed));
        });
    }

    /**
     * 异步终止应用集合中的进程。
     *
     * @param appsToKill 应用快照
     */
    public void killAll(List<AppInfo> appsToKill) {
        backgroundExecutor.submit(() -> {
            boolean anyProcessKilled = appsToKill.stream()
                    .map(appInfo -> processManager.killProcess(AppLauncherStatusService.resolveCheckName(appInfo)))
                    .reduce(false, Boolean::logicalOr);

            if (!anyProcessKilled) {
                logger.info("没有正在运行的进程");
            }
        });
    }

    /**
     * 重启应用程序。
     *
     * @param appInfo 应用配置
     */
    private void restartApplication(AppInfo appInfo) {
        String path = appInfo.getAppPath();
        String checkName = AppLauncherStatusService.resolveCheckName(appInfo);
        if (processManager.isProcessRunning(checkName)) {
            logger.info("正在停止运行中的进程: " + path);
            if (processManager.killProcess(checkName)) {
                logger.info("成功停止进程: " + path);
                if (!sleep(PROCESS_CHECK_DELAY_MS)) {
                    return;
                }
            } else {
                logger.error("停止进程失败: " + path);
                return;
            }
        }
        launchApplication(appInfo);
    }

    private void launchApplication(AppInfo appInfo) {
        String path = appInfo.getAppPath();
        try {
            Process process = processManager.startProcess(path);
            if (process != null) {
                logger.info("成功启动: " + path);
                Platform.runLater(updateAppList);
                logger.info("UI更新完成");
            }
        } catch (IOException e) {
            logger.error("启动失败: " + path + " - " + e.getMessage());
        }
    }

    private long calculateLaunchDelay(String appPath) {
        String lowerPath = appPath.toLowerCase();
        if (lowerPath.endsWith(".exe")) {
            return EXE_LAUNCH_DELAY_MS;
        }
        if (lowerPath.endsWith(".bat") || lowerPath.endsWith(".cmd")) {
            return SCRIPT_LAUNCH_DELAY_MS;
        }
        return DEFAULT_LAUNCH_DELAY_MS;
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
}
