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
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 启动项工具的启动、停止和批量进程操作。
 *
 * @author wwj
 */
public class AppLauncherExecutionService {
    private static final long PROCESS_CHECK_DELAY_MS = 800;

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
     * @param launchDelayMillis 相邻启动项之间的等待时间
     * @param onFinished 完成回调
     */
    public Future<?> launchAll(List<AppInfo> appsToLaunch, int launchDelayMillis,
                               Runnable onFinished) {
        int effectiveDelay = Math.max(AppLauncherSettingsStore.MIN_LAUNCH_DELAY_MILLIS,
                Math.min(AppLauncherSettingsStore.MAX_LAUNCH_DELAY_MILLIS,
                        launchDelayMillis));
        logger.info("开始按列表顺序启动 " + appsToLaunch.size() + " 个应用程序，相邻间隔 "
                + formatDelay(effectiveDelay));

        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean completionNotified = new AtomicBoolean();
        FutureTask<Void> task = new FutureTask<>(() -> {
            int successCount = 0;
            int totalCount = appsToLaunch.size();
            boolean cancelled = false;
            List<AppInfo> launchedApps = new ArrayList<>();
            Map<String, Boolean> initialStates;
            try {
                initialStates = processManager.captureRunningStates(appsToLaunch);
            } catch (IOException e) {
                logger.error("无法确认现有进程状态，已取消批量启动: " + e.getMessage());
                return null;
            }

            for (int i = 0; i < totalCount; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    cancelled = true;
                    break;
                }
                if (i > 0 && effectiveDelay > 0) {
                    logger.info("等待 " + formatDelay(effectiveDelay) + " 后启动下一项...");
                    if (!sleep(effectiveDelay)) {
                        cancelled = true;
                        break;
                    }
                }
                AppInfo appInfo = appsToLaunch.get(i);
                int currentIndex = i + 1;
                logger.info(String.format("启动进度 %d/%d: %s",
                        currentIndex, totalCount, appInfo.getAppPath()));
                try {
                    Boolean knownRunning = initialStates.get(appInfo.getAppPath());
                    if (!restartApplication(appInfo, knownRunning)) {
                        if (Thread.currentThread().isInterrupted()) {
                            cancelled = true;
                            break;
                        }
                        continue;
                    }
                    successCount++;
                    launchedApps.add(appInfo);
                } catch (RuntimeException e) {
                    logger.error("启动失败: " + appInfo.getAppPath() + " - " + e.getMessage());
                }
            }

            if (cancelled || Thread.currentThread().isInterrupted()) {
                logger.info(String.format("批量启动已停止: 已启动 %d/%d", successCount, totalCount));
            } else {
                logger.info(String.format("批量启动完成: 成功 %d/%d", successCount, totalCount));
                if (!launchedApps.isEmpty()) {
                    statusService.verifyBatchLaunchStatus(launchedApps);
                }
            }
            return null;
        }) {
            @Override
            public void run() {
                started.set(true);
                try {
                    super.run();
                } finally {
                    notifyCompletion();
                }
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled && !started.get()) {
                    notifyCompletion();
                }
                return cancelled;
            }

            private void notifyCompletion() {
                if (completionNotified.compareAndSet(false, true)) {
                    notifyFinished(onFinished);
                }
            }
        };
        try {
            backgroundExecutor.execute(task);
            return task;
        } catch (RejectedExecutionException e) {
            logger.error("批量启动任务已被拒绝");
            task.cancel(false);
            return task;
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

    private static String formatDelay(int delayMillis) {
        if (delayMillis == 0) {
            return "0 秒";
        }
        return delayMillis % 1_000 == 0
                ? delayMillis / 1_000 + " 秒"
                : String.format(java.util.Locale.ROOT, "%.1f 秒", delayMillis / 1_000.0);
    }

    private void notifyFinished(Runnable onFinished) {
        Platform.runLater(onFinished);
    }
}
