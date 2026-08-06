package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.model.AppProcessStatus;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动项工具的进程状态检查与批量验证逻辑。
 *
 * @author wwj
 */
public class AppLauncherStatusService {
    private final ModuleLogger logger;
    private final AppProcessManager processManager;
    private final Map<String, AppProcessStatus> processStatusCache;
    private final Runnable refreshListView;
    private final Runnable scheduleUiUpdate;
    private final AtomicBoolean statusCheckInProgress = new AtomicBoolean();

    private long totalStatusCheckMillis;
    private int statusCheckCount;

    /**
     * 创建启动项状态服务。
     *
     * @param logger 日志输出接口
     * @param processManager 进程管理器
     * @param processStatusCache 状态缓存
     * @param refreshListView 刷新列表回调
     * @param scheduleUiUpdate 合并调度刷新回调
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
     * 定期检查全部应用状态。每轮只读取一次系统进程快照。
     *
     * @param appInfos 应用快照
     * @param cacheTtlMillis 状态缓存有效期
     */
    public void checkAllProcessStatus(List<AppInfo> appInfos, long cacheTtlMillis) {
        if (appInfos.isEmpty() || !statusCheckInProgress.compareAndSet(false, true)) {
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            List<AppInfo> staleApps = appInfos.stream()
                    .filter(appInfo -> {
                        AppProcessStatus cached = processStatusCache.get(appInfo.getAppPath());
                        return cached == null || cached.isExpired(cacheTtlMillis);
                    })
                    .toList();
            if (staleApps.isEmpty()) {
                return;
            }

            Map<String, Boolean> states = processManager.captureRunningStates(staleApps);
            long duration = System.currentTimeMillis() - startTime;
            if (updateStatusCache(states, duration)) {
                scheduleUiUpdate.run();
            }
        } catch (IOException e) {
            logger.debug("进程快照读取失败: " + e.getMessage());
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            totalStatusCheckMillis += duration;
            statusCheckCount++;
            if (statusCheckCount % 100 == 0) {
                logger.debug(String.format("状态检查性能: 平均耗时 %dms, 检查次数 %d",
                        totalStatusCheckMillis / statusCheckCount, statusCheckCount));
            }
            statusCheckInProgress.set(false);
        }
    }

    /**
     * 使用单次系统进程快照验证批量启动结果。
     *
     * @param launchedApps 已启动应用
     */
    public void verifyBatchLaunchStatus(List<AppInfo> launchedApps) {
        if (launchedApps.isEmpty()) {
            return;
        }
        if (!statusCheckInProgress.compareAndSet(false, true)) {
            logger.debug("已有状态检查正在执行，跳过重复的批量验证");
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            Map<String, Boolean> states = processManager.captureRunningStates(launchedApps);
            long duration = System.currentTimeMillis() - startTime;
            boolean changed = updateStatusCache(states, duration);
            Platform.runLater(() -> {
                if (changed) {
                    refreshListView.run();
                }
                logger.info("批量启动状态验证完成");
            });
        } catch (IOException e) {
            logger.error("批量启动状态验证失败: " + e.getMessage());
        } finally {
            statusCheckInProgress.set(false);
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
        if (!statusCheckInProgress.compareAndSet(false, true)) {
            Platform.runLater(() -> logger.info("状态检查正在进行中，请稍候"));
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            Map<String, Boolean> states = processManager.captureRunningStates(appsToCheck);
            long duration = System.currentTimeMillis() - startTime;
            boolean changed = updateStatusCache(states, duration);
            Platform.runLater(() -> {
                if (changed) {
                    refreshListView.run();
                }
                logger.info(String.format("状态检查完成，共 %d 个应用，耗时 %dms",
                        appsToCheck.size(), duration));
            });
        } catch (IOException e) {
            Platform.runLater(() -> logger.error("状态检查失败: " + e.getMessage()));
        } finally {
            statusCheckInProgress.set(false);
        }
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

    private boolean updateStatusCache(Map<String, Boolean> states, long checkDuration) {
        boolean changed = false;
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            changed |= putStatus(entry.getKey(), entry.getValue(), checkDuration);
        }
        return changed;
    }

    private boolean putStatus(String appPath, boolean running, long checkDuration) {
        AppProcessStatus previous = processStatusCache.put(
                appPath, new AppProcessStatus(running, checkDuration));
        return previous == null || previous.isRunning() != running;
    }
}
