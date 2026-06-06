package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.model.AppProcessStatus;
import plugin.javafxtools.service.AppLauncherExecutionService;
import plugin.javafxtools.service.AppLauncherLifecycleService;
import plugin.javafxtools.service.AppLauncherListActionService;
import plugin.javafxtools.service.AppLauncherListViewSupport;
import plugin.javafxtools.service.AppLauncherRuntimeActionService;
import plugin.javafxtools.service.AppLauncherStore;
import plugin.javafxtools.service.AppLauncherStatusService;
import plugin.javafxtools.service.AppLauncherUiRefreshService;
import plugin.javafxtools.service.AppProcessManager;

import java.util.*;
import java.util.concurrent.*;

/**
 * 应用程序启动器控制器，保留 FXML 入口并协调各启动项服务。
 */
public class AppLauncherController extends BaseController {
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "AppLauncher-Background");
        t.setDaemon(true);
        return t;
    });

    // 记录最后选中的索引
    private volatile int lastSelectedIndex = -1;

    private static final int PROCESS_CHECK_INTERVAL_MS = 1000 * 10;

    // FXML注入的UI组件
    @FXML
    private TextField appPathField;

    @FXML
    private ListView<AppInfo> appListView;

    @FXML
    private Button browseButton, addButton, launchSingleButton, launchAllButton,
            killProcessButton, removeButton, clearButton, refreshStatusButton;

    @FXML
    private TextArea logArea;

    // 数据存储 - 使用线程安全的集合
    private final List<AppInfo> appInfos = Collections.synchronizedList(new ArrayList<>());

    private volatile Map<String, String> launcherProcessMap = new ConcurrentHashMap<>();

    private volatile Stage primaryStage;

    // 优化的状态检查执行器
    private final ScheduledExecutorService statusCheckExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AppLauncher-StatusCheck");
                t.setDaemon(true);
                return t;
            });

    // 智能进程状态缓存
    private final Map<String, AppProcessStatus> processStatusCache = new ConcurrentHashMap<>();

    private final AppLauncherUiRefreshService uiRefreshService =
            new AppLauncherUiRefreshService(this::refreshListViewOptimized);

    private final AppProcessManager processManager =
            new AppProcessManager(this, backgroundExecutor, processStatusCache, uiRefreshService::scheduleUpdate);

    private final AppLauncherStatusService statusService =
            new AppLauncherStatusService(this, processManager, processStatusCache,
                    this::refreshListViewOptimized, uiRefreshService::scheduleUpdate);

    private final AppLauncherStore appStore = new AppLauncherStore(this);

    private final AppLauncherExecutionService executionService =
            new AppLauncherExecutionService(this, processManager, statusService,
                    backgroundExecutor, processStatusCache, this::updateAppList);

    private AppLauncherListViewSupport listViewSupport;

    private final AppLauncherListActionService listActionService =
            new AppLauncherListActionService(this, appInfos, () -> launcherProcessMap,
                    this::updateAppList, this::saveAppInfosAsync,
                    index -> listViewSupport.selectAndFocus(index),
                    removed -> executionService.killProcess(removed, killed -> {
                        if (killed) {
                            info("已停止并移除: " + removed.getAppPath());
                        }
                    }),
                    executionService::killAll);

    private final AppLauncherLifecycleService lifecycleService =
            new AppLauncherLifecycleService(this, processManager, appInfos, processStatusCache,
                    () -> launcherProcessMap, uiRefreshService, statusCheckExecutor, backgroundExecutor);

    private final AppLauncherRuntimeActionService runtimeActionService =
            new AppLauncherRuntimeActionService(this, executionService, statusService, backgroundExecutor,
                    uiRefreshService, this::selectedIndex, this::snapshotAppInfos, this::updateAppList);

    /**
     * 设置主舞台
     */
    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    /**
     * 获取日志区域
     */
    @Override
    public TextArea getLogArea() {
        return logArea;
    }

    /**
     * 优化的资源清理方法 - 不强制终止独立进程
     */
    @Override
    public void cleanup() {
        lifecycleService.cleanup();
    }

    /**
     * 优化的初始化方法
     */
    @FXML
    public void initialize() {
        // 优化的定期状态检查任务
        statusCheckExecutor.scheduleWithFixedDelay(() -> {
            if (!appInfos.isEmpty()) {
                statusService.checkAllProcessStatus(
                        snapshotAppInfos(), PROCESS_CHECK_INTERVAL_MS * 2L, backgroundExecutor);
            }
        }, 1000, PROCESS_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        backgroundExecutor.submit(() -> {
            launcherProcessMap = appStore.loadProcessMap();
            Platform.runLater(this::initializeUI);
        });
    }

    /**
     * 初始化UI组件
     */
    private void initializeUI() {
        try {
            // 设置UI提示文本
            appPathField.setPromptText("输入应用程序路径或点击浏览...");
            logArea.setPromptText("操作日志将显示在这里...");

            listViewSupport = new AppLauncherListViewSupport(this, appListView, processStatusCache,
                    () -> lastSelectedIndex, index -> lastSelectedIndex = index);
            listViewSupport.initialize();

            // 异步加载应用信息
            backgroundExecutor.submit(() -> {
                loadAppInfos();
                Platform.runLater(() -> info("应用程序启动器初始化完成"));
            });
        } catch (Exception e) {
            error("UI初始化失败: " + e.getMessage());
        }
    }

    /**
     * 优化的ListView刷新方法
     */
    private void refreshListViewOptimized() {
        if (listViewSupport != null) {
            listViewSupport.refresh();
        }
    }

    private int selectedIndex() {
        return listViewSupport == null ? -1 : listViewSupport.selectedIndex();
    }

    /**
     * 浏览文件处理
     */
    @FXML
    private void handleBrowse() {
        listActionService.browse(primaryStage, appPathField);
    }

    /**
     * 添加应用处理
     */
    @FXML
    private void handleAdd() {
        listActionService.add(appPathField);
    }

    /**
     * 启动单个应用 - 优化状态更新
     */
    @FXML
    private void handleLaunchSingle() {
        runtimeActionService.launchSingle();
    }

    /**
     * 彻底修复的批量启动方法 - 解决进程状态显示和内存问题
     */
    @FXML
    private void handleLaunchAll() {
        runtimeActionService.launchAll();
    }

    /**
     * 结束进程处理
     */
    @FXML
    private void handleKillProcess() {
        runtimeActionService.killProcess(killProcessButton);
    }

    /**
     * 移除应用处理
     */
    @FXML
    private void handleRemove() {
        listActionService.remove(listViewSupport.selectedIndex());
    }

    /**
     * 上移应用处理
     */
    @FXML
    private void handleMoveUp() {
        lastSelectedIndex = listActionService.moveUp(listViewSupport.selectedIndex(), lastSelectedIndex);
    }

    /**
     * 下移应用处理
     */
    @FXML
    private void handleMoveDown() {
        lastSelectedIndex = listActionService.moveDown(listViewSupport.selectedIndex(), lastSelectedIndex);
    }

    /**
     * 清空列表处理
     */
    @FXML
    private void handleClear() {
        listActionService.clear();
    }


    /**
     * 优化的手动刷新进程状态 - 解决内存占用问题
     */
    @FXML
    private void handleRefreshStatus() {
        runtimeActionService.refreshStatus();
    }

    /**
     * 高度优化的应用列表更新方法
     */
    private void updateAppList() {
        if (listViewSupport != null) {
            listViewSupport.updateList(snapshotAppInfos());
        }
    }

    /**
     * 加载应用信息
     */
    private void loadAppInfos() {
        List<AppInfo> loadedAppInfos = appStore.loadAppInfos(launcherProcessMap);
        appInfos.clear();
        appInfos.addAll(loadedAppInfos);
        updateAppList();
        info("已加载 " + appInfos.size() + " 个应用程序路径");
    }

    /**
     * 异步保存应用信息
     */
    private void saveAppInfosAsync() {
        List<AppInfo> appInfoSnapshot = snapshotAppInfos();
        backgroundExecutor.submit(() -> appStore.saveAppInfos(appInfoSnapshot));
    }

    private List<AppInfo> snapshotAppInfos() {
        synchronized (appInfos) {
            return new ArrayList<>(appInfos);
        }
    }
}
