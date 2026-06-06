package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.model.AppProcessStatus;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 启动项工具的进程状态检查与批量验证逻辑。
 *
 * @author wwj
 */
public class AppLauncherStatusService {
    private static final int MANUAL_STATUS_BATCH_SIZE = 5;
    private static final int MANUAL_STATUS_BATCH_DELAY_MS = 200;
    private static final int BATCH_VERIFY_DELAY_MS = 1000;

    private final ModuleLogger logger;
    private final AppProcessManager processManager;
    private final Map<String, AppProcessStatus> processStatusCache;
    private final Runnable refreshListView;
    private final Runnable scheduleUiUpdate;

    private volatile boolean statusCheckInProgress = false;
    private volatile long lastStatusCheckTime = 0;
    private volatile int statusCheckCount = 0;

    private final Object statusCheckLock = new Object();

    /**
     * 创建启动项状态服务。
     *
     * @param logger 日志输出接口
     * @param processManager 进程管理器
     * @param processStatusCache 状态缓存
     * @param refreshListView 刷新列表回调
     * @param scheduleUiUpdate 批量调度刷新回调
     */
    public AppLauncherStatusService(ModuleLogger logger,
                                    AppProcessManager processManager,
                                    Map<String, AppProcessStatus> processStatusCache,
                                    Runnable refreshListView,
                                    Runnable scheduleUiUpdate) {
        this.logger = logger;
        this.processManager = processManager;
        this.processStatusCache = processStatusCache;
        this.refreshListView = refreshListView;
        this.scheduleUiUpdate = scheduleUiUpdate;
    }

    /**
     * 定期检查全部应用状态。
     *
     * @param appInfos 应用快照
     * @param cacheTtlMillis 状态缓存有效期
     * @param backgroundExecutor 后台执行器
     */
    public void checkAllProcessStatus(List<AppInfo> appInfos,
                                      long cacheTtlMillis,
                                      ExecutorService backgroundExecutor) {
        synchronized (statusCheckLock) {
            if (statusCheckInProgress || appInfos.isEmpty()) {
                return;
            }
            statusCheckInProgress = true;
        }

        long startTime = System.currentTimeMillis();
        int currentTaskCount = getCurrentTaskCount(backgroundExecutor);
        if (currentTaskCount > 0) {
            logger.info("当前还未完成的任务数量:" + currentTaskCount);
        }

        try {
            for (AppInfo appInfo : appInfos) {
                String checkName = resolveCheckName(appInfo);
                AppProcessStatus cached = processStatusCache.get(appInfo.getAppPath());
                if (cached != null && !cached.isExpired(cacheTtlMillis)) {
                    continue;
                }

                long checkStart = System.currentTimeMillis();
                boolean isRunning = processManager.isProcessRunning(checkName);
                long duration = System.currentTimeMillis() - checkStart;
                processStatusCache.put(appInfo.getAppPath(), new AppProcessStatus(isRunning, duration));
            }
            scheduleUiUpdate.run();
        } finally {
            lastStatusCheckTime = System.currentTimeMillis() - startTime;
            statusCheckCount++;
            if (statusCheckCount % 100 == 0) {
                logger.debug(String.format("状态检查性能: 平均耗时 %dms, 检查次数 %d",
                        lastStatusCheckTime, statusCheckCount));
            }
            synchronized (statusCheckLock) {
                statusCheckInProgress = false;
            }
        }
    }

    /**
     * 强制检查单个应用进程状态。
     *
     * @param appInfo 应用配置
     */
    public void forceCheckSingleProcessStatus(AppInfo appInfo) {
        String checkName = resolveCheckName(appInfo);
        long checkStart = System.currentTimeMillis();
        boolean isRunning = processManager.isProcessRunning(checkName, true);
        long checkDuration = System.currentTimeMillis() - checkStart;

        processStatusCache.put(appInfo.getAppPath(), new AppProcessStatus(isRunning, checkDuration));
        Platform.runLater(() -> {
            refreshListView.run();
            logger.debug(String.format("强制检查进程状态: %s -> %s (耗时: %dms)",
                    checkName, isRunning ? "运行中" : "未运行", checkDuration));
        });
    }

    /**
     * 分批验证批量启动结果。
     *
     * @param launchedApps 已启动应用
     */
    public void verifyBatchLaunchStatus(List<AppInfo> launchedApps) {
        if (launchedApps.isEmpty()) {
            return;
        }

        try {
            logger.info("开始验证批量启动的进程状态...");
            int verifiedCount = 0;
            for (AppInfo appInfo : launchedApps) {
                String checkName = resolveCheckName(appInfo);
                long checkStart = System.currentTimeMillis();
                boolean isRunning = processManager.isProcessRunning(checkName, true);
                long checkDuration = System.currentTimeMillis() - checkStart;

                processStatusCache.put(appInfo.getAppPath(), new AppProcessStatus(isRunning, checkDuration));
                verifiedCount++;
                logger.debug(String.format("验证进度 %d/%d: %s -> %s",
                        verifiedCount, launchedApps.size(), checkName, isRunning ? "运行中" : "未运行"));
                Platform.runLater(refreshListView);
                Thread.sleep(BATCH_VERIFY_DELAY_MS);
            }

            Platform.runLater(() -> {
                refreshListView.run();
                logger.info("批量启动状态验证完成，所有进程状态已更新");
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Platform.runLater(() -> logger.error("状态验证被中断"));
        }
    }

    /**
     * 手动刷新全部应用状态。
     *
     * @param appsToCheck 应用快照
     */
    public void lightweightStatusCheck(List<AppInfo> appsToCheck) {
        if (appsToCheck.isEmpty()) {
            Platform.runLater(() -> logger.info("没有应用程序需要检查"));
            return;
        }

        logger.info("开始轻量级状态检查，共 " + appsToCheck.size() + " 个应用...");
        int checkedCount = 0;

        for (int i = 0; i < appsToCheck.size(); i += MANUAL_STATUS_BATCH_SIZE) {
            int endIndex = Math.min(i + MANUAL_STATUS_BATCH_SIZE, appsToCheck.size());
            List<AppInfo> batch = appsToCheck.subList(i, endIndex);

            for (AppInfo appInfo : batch) {
                String checkName = resolveCheckName(appInfo);
                long checkStart = System.currentTimeMillis();
                boolean isRunning = processManager.isProcessRunning(checkName, true);
                long checkDuration = System.currentTimeMillis() - checkStart;
                processStatusCache.put(appInfo.getAppPath(), new AppProcessStatus(isRunning, checkDuration));
                checkedCount++;
            }

            int finalCheckedCount = checkedCount;
            Platform.runLater(() -> {
                refreshListView.run();
                logger.debug(String.format("状态检查进度: %d/%d", finalCheckedCount, appsToCheck.size()));
            });

            if (endIndex < appsToCheck.size() && !sleepBetweenBatches(MANUAL_STATUS_BATCH_DELAY_MS)) {
                return;
            }
        }

        Platform.runLater(() -> logger.info("手动状态检查完成"));
    }

    /**
     * 解析应用状态检查使用的进程名。
     *
     * @param appInfo 应用配置
     * @return 进程名
     */
    public static String resolveCheckName(AppInfo appInfo) {
        String processName = appInfo.getProcessName();
        if (processName != null && !processName.isEmpty()) {
            return processName;
        }
        return new File(appInfo.getAppPath()).getName();
    }

    private boolean sleepBetweenBatches(int delayMillis) {
        try {
            Thread.sleep(delayMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Platform.runLater(() -> logger.error("状态检查被中断"));
            return false;
        }
    }

    private static int getCurrentTaskCount(ExecutorService threadPoolExecutor) {
        if (threadPoolExecutor instanceof ThreadPoolExecutor executor) {
            return executor.getActiveCount() + executor.getQueue().size();
        }
        return -1;
    }
}
