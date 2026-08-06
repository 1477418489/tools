package plugin.javafxtools.service;

import javafx.scene.control.Button;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 启动项工具的运行时按钮动作协调。
 *
 * @author wwj
 */
public class AppLauncherRuntimeActionService {
    private final ModuleLogger logger;
    private final AppLauncherExecutionService executionService;
    private final AppLauncherStatusService statusService;
    private final ExecutorService backgroundExecutor;
    private final AppLauncherUiRefreshService uiRefreshService;
    private final IntSupplier selectedIndexSupplier;
    private final Supplier<List<AppInfo>> appInfosSnapshotSupplier;
    private final Runnable updateAppList;
    private final Consumer<Boolean> launchStateConsumer;
    private final AtomicBoolean launchInProgress = new AtomicBoolean();

    /**
     * 创建运行时动作服务。
     *
     * @param logger 日志输出接口
     * @param executionService 启动停止服务
     * @param statusService 状态检查服务
     * @param backgroundExecutor 后台执行器
     * @param uiRefreshService UI 刷新服务
     * @param selectedIndexSupplier 选中索引读取器
     * @param appInfosSnapshotSupplier 应用配置快照读取器
     * @param updateAppList 列表刷新回调
     * @param launchStateConsumer 长操作运行状态回调
     */
    public AppLauncherRuntimeActionService(ModuleLogger logger,
                                           AppLauncherExecutionService executionService,
                                           AppLauncherStatusService statusService,
                                           ExecutorService backgroundExecutor,
                                           AppLauncherUiRefreshService uiRefreshService,
                                           IntSupplier selectedIndexSupplier,
                                           Supplier<List<AppInfo>> appInfosSnapshotSupplier,
                                           Runnable updateAppList,
                                           Consumer<Boolean> launchStateConsumer) {
        this.logger = logger;
        this.executionService = executionService;
        this.statusService = statusService;
        this.backgroundExecutor = backgroundExecutor;
        this.uiRefreshService = uiRefreshService;
        this.selectedIndexSupplier = selectedIndexSupplier;
        this.appInfosSnapshotSupplier = appInfosSnapshotSupplier;
        this.updateAppList = updateAppList;
        this.launchStateConsumer = launchStateConsumer;
    }

    /**
     * 启动当前选中的应用。
     */
    public void launchSingle() {
        AppInfo selectedApp = selectedApp("请先选择要启动的应用程序");
        if (selectedApp != null && beginLaunch()) {
            executionService.launchSingle(selectedApp, this::finishLaunch);
        }
    }

    /**
     * 批量启动全部应用。
     */
    public void launchAll() {
        List<AppInfo> appInfos = appInfosSnapshotSupplier.get();
        if (appInfos.isEmpty()) {
            logger.error("应用程序列表为空");
            return;
        }
        if (beginLaunch()) {
            executionService.launchAll(appInfos, this::finishLaunch);
        }
    }

    /**
     * 结束当前选中的应用进程。
     *
     * @param killProcessButton 结束进程按钮
     */
    public void killProcess(Button killProcessButton) {
        AppInfo selectedApp = selectedApp("请先选择要结束的应用程序");
        if (selectedApp == null) {
            return;
        }

        killProcessButton.setDisable(true);
        executionService.killProcess(selectedApp, killed -> {
            killProcessButton.setDisable(false);
            if (killed) {
                logger.info("成功结束进程: " + selectedApp.getAppPath());
                updateAppList.run();
            } else {
                logger.info("未找到运行中的进程: " + selectedApp.getAppPath());
            }
        });
    }

    /**
     * 手动刷新全部应用进程状态。
     */
    public void refreshStatus() {
        logger.info("手动刷新进程状态...");
        if (!uiRefreshService.tryStartUpdate()) {
            logger.info("刷新操作正在进行中，请稍候...");
            return;
        }

        try {
            backgroundExecutor.submit(() -> {
                try {
                    statusService.lightweightStatusCheck(appInfosSnapshotSupplier.get());
                } finally {
                    uiRefreshService.finishUpdate();
                }
            });
        } catch (RejectedExecutionException e) {
            uiRefreshService.finishUpdate();
            logger.error("状态刷新任务已被拒绝");
        }
    }

    private AppInfo selectedApp(String missingSelectionMessage) {
        int selectedIndex = selectedIndexSupplier.getAsInt();
        if (selectedIndex < 0) {
            logger.error(missingSelectionMessage);
            return null;
        }

        List<AppInfo> appInfos = appInfosSnapshotSupplier.get();
        if (selectedIndex >= appInfos.size()) {
            logger.error("选中的应用程序已不存在，请重新选择");
            return null;
        }
        return appInfos.get(selectedIndex);
    }

    private boolean beginLaunch() {
        if (!launchInProgress.compareAndSet(false, true)) {
            logger.info("启动操作正在进行中，请稍候");
            return false;
        }
        launchStateConsumer.accept(true);
        return true;
    }

    private void finishLaunch() {
        launchInProgress.set(false);
        launchStateConsumer.accept(false);
        updateAppList.run();
    }
}
