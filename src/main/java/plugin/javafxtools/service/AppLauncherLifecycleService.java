package plugin.javafxtools.service;

import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.model.AppProcessStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 启动项工具的资源清理与执行器关闭逻辑。
 *
 * @author wwj
 */
public class AppLauncherLifecycleService {
    private final ModuleLogger logger;
    private final AppProcessManager processManager;
    private final List<AppInfo> appInfos;
    private final Map<String, AppProcessStatus> processStatusCache;
    private final Supplier<Map<String, String>> launcherProcessMapSupplier;
    private final AppLauncherUiRefreshService uiRefreshService;
    private final ScheduledExecutorService statusCheckExecutor;
    private final ExecutorService backgroundExecutor;

    /**
     * 创建生命周期服务。
     *
     * @param logger 日志输出接口
     * @param processManager 进程管理器
     * @param appInfos 应用配置集合
     * @param processStatusCache 进程状态缓存
     * @param launcherProcessMapSupplier 进程映射读取器
     * @param uiRefreshService UI 刷新服务
     * @param statusCheckExecutor 状态检查执行器
     * @param backgroundExecutor 后台执行器
     */
    public AppLauncherLifecycleService(ModuleLogger logger,
                                       AppProcessManager processManager,
                                       List<AppInfo> appInfos,
                                       Map<String, AppProcessStatus> processStatusCache,
                                       Supplier<Map<String, String>> launcherProcessMapSupplier,
                                       AppLauncherUiRefreshService uiRefreshService,
                                       ScheduledExecutorService statusCheckExecutor,
                                       ExecutorService backgroundExecutor) {
        this.logger = logger;
        this.processManager = processManager;
        this.appInfos = appInfos;
        this.processStatusCache = processStatusCache;
        this.launcherProcessMapSupplier = launcherProcessMapSupplier;
        this.uiRefreshService = uiRefreshService;
        this.statusCheckExecutor = statusCheckExecutor;
        this.backgroundExecutor = backgroundExecutor;
    }

    /**
     * 清理控制器资源，保留独立启动的外部程序继续运行。
     */
    public void cleanup() {
        logger.info("开始清理资源...");
        uiRefreshService.reset();

        shutdownExecutorGracefully("StatusCheck", statusCheckExecutor, 3);
        shutdownExecutorGracefully("Background", backgroundExecutor, 5);

        logger.info("保留独立启动的进程继续运行，仅解除进程跟踪");
        try {
            processManager.detachManagedProcessesOnly();
        } catch (RuntimeException e) {
            logger.error("解除进程跟踪时出错: " + e.getMessage());
        }

        appInfos.clear();
        processStatusCache.clear();
        launcherProcessMapSupplier.get().clear();

        logger.info("资源清理完成，独立进程将继续运行");
    }

    private void shutdownExecutorGracefully(String name, ExecutorService executor, int timeoutSeconds) {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        try {
            executor.shutdown();
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.debug(name + "执行器未在" + timeoutSeconds + "秒内关闭，强制关闭");
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.error(name + "执行器强制关闭失败");
                }
            } else {
                logger.debug(name + "执行器已优雅关闭");
            }
        } catch (InterruptedException e) {
            logger.error(name + "执行器关闭被中断");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
