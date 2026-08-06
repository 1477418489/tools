package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
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

    private static final long PROCESS_CHECK_INTERVAL_SECONDS = 30;
    private static final long PROCESS_STATUS_CACHE_TTL_MILLIS = 25_000;

    // FXML注入的UI组件
    @FXML
    private TextField appPathField;

    @FXML
    private ListView<AppInfo> appListView;

    @FXML
    private Button browseButton, addButton, launchSingleButton, launchAllButton,
            killProcessButton, removeButton, clearButton, refreshStatusButton;

    @FXML
    private Button moveUpButton, moveDownButton;

    @FXML
    private Label appCountLabel;

    @FXML
    private TextArea logArea;

    // 数据存储 - 使用线程安全的集合
    private final List<AppInfo> appInfos = Collections.synchronizedList(new ArrayList<>());

    private volatile Map<String, String> launcherProcessMap = new ConcurrentHashMap<>();

    private volatile Stage primaryStage;

    private boolean launchOperationRunning;

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
            new AppProcessManager(this, processStatusCache, uiRefreshService::scheduleUpdate);

    private final AppLauncherStatusService statusService =
            new AppLauncherStatusService(this, processManager, processStatusCache,
                    this::refreshListViewOptimized, uiRefreshService::scheduleUpdate);

    private final AppLauncherStore appStore = new AppLauncherStore(this);

    private final AppLauncherExecutionService executionService =
            new AppLauncherExecutionService(this, processManager, statusService,
                    backgroundExecutor, processStatusCache);

    private AppLauncherListViewSupport listViewSupport;

    private final AppLauncherListActionService listActionService =
            new AppLauncherListActionService(this, appInfos, () -> launcherProcessMap,
                    this::updateAppList, this::saveAppInfos,
                    index -> listViewSupport.selectAndFocus(index),
                    removed -> executionService.killProcess(removed, killed -> {
                        if (killed) {
                            info("已停止并移除: " + removed.getAppPath());
                        }
                    }),
                    executionService::killAll);

    private final AppLauncherLifecycleService lifecycleService =
            new AppLauncherLifecycleService(this, processManager, appInfos, processStatusCache,
                    () -> launcherProcessMap, uiRefreshService, statusCheckExecutor,
                    backgroundExecutor);

    private final AppLauncherRuntimeActionService runtimeActionService =
            new AppLauncherRuntimeActionService(this, executionService, statusService, backgroundExecutor,
                    uiRefreshService, this::selectedIndex, this::snapshotAppInfos, this::updateAppList,
                    this::setLaunchOperationRunning);

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
        disableListActionsUntilLoaded();
        appPathField.textProperty().addListener(
                (observable, oldValue, newValue) -> updateActionButtonStates());
        // 优化的定期状态检查任务
        statusCheckExecutor.scheduleWithFixedDelay(() -> {
            if (!appInfos.isEmpty()) {
                statusService.checkAllProcessStatus(
                        snapshotAppInfos(), PROCESS_STATUS_CACHE_TTL_MILLIS);
            }
        }, PROCESS_CHECK_INTERVAL_SECONDS, PROCESS_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

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
            appListView.getSelectionModel().selectedItemProperty().addListener(
                    (observable, oldValue, newValue) -> updateActionButtonStates());
            appListView.getItems().addListener(
                    (ListChangeListener<AppInfo>) change -> updateActionButtonStates());
            updateActionButtonStates();

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
        Runnable refresh = () -> {
            if (listViewSupport != null) {
                listViewSupport.refresh();
                updateActionButtonStates();
            }
        };
        if (Platform.isFxApplicationThread()) {
            refresh.run();
        } else {
            Platform.runLater(refresh);
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
        List<AppInfo> snapshot = snapshotAppInfos();
        processManager.retainCachesFor(snapshot);
        if (listViewSupport != null) {
            listViewSupport.updateList(snapshot);
            if (Platform.isFxApplicationThread()) {
                updateActionButtonStates();
            } else {
                Platform.runLater(this::updateActionButtonStates);
            }
        }
    }

    /**
     * 加载应用信息
     */
    private void loadAppInfos() {
        List<AppInfo> loadedAppInfos = appStore.loadAppInfos();
        appInfos.clear();
        appInfos.addAll(loadedAppInfos);
        updateAppList();
        info("已加载 " + appInfos.size() + " 个应用程序路径");
        if (!loadedAppInfos.isEmpty()) {
            statusService.checkAllProcessStatus(
                    loadedAppInfos, PROCESS_STATUS_CACHE_TTL_MILLIS);
        }
    }

    private boolean saveAppInfos(List<AppInfo> updatedAppInfos) {
        return appStore.saveAppInfos(updatedAppInfos);
    }

    private List<AppInfo> snapshotAppInfos() {
        synchronized (appInfos) {
            return new ArrayList<>(appInfos);
        }
    }

    private void disableListActionsUntilLoaded() {
        addButton.setDisable(true);
        launchSingleButton.setDisable(true);
        launchAllButton.setDisable(true);
        killProcessButton.setDisable(true);
        removeButton.setDisable(true);
        clearButton.setDisable(true);
        refreshStatusButton.setDisable(true);
        moveUpButton.setDisable(true);
        moveDownButton.setDisable(true);
    }

    private void updateActionButtonStates() {
        if (appListView == null) {
            return;
        }
        int itemCount = appListView.getItems().size();
        int selectedIndex = appListView.getSelectionModel().getSelectedIndex();
        AppInfo selected = appListView.getSelectionModel().getSelectedItem();
        AppProcessStatus selectedStatus = selected == null
                ? null
                : processStatusCache.get(selected.getAppPath());
        boolean selectedRunning = selectedStatus != null && selectedStatus.isRunning();
        boolean hasSelection = selectedIndex >= 0;
        boolean hasItems = itemCount > 0;

        appPathField.setDisable(launchOperationRunning);
        browseButton.setDisable(launchOperationRunning);
        addButton.setDisable(launchOperationRunning
                || listViewSupport == null
                || appPathField.getText() == null
                || appPathField.getText().isBlank());
        launchSingleButton.setDisable(launchOperationRunning || !hasSelection || selectedRunning);
        killProcessButton.setDisable(launchOperationRunning || !hasSelection || !selectedRunning);
        removeButton.setDisable(launchOperationRunning || !hasSelection);
        moveUpButton.setDisable(launchOperationRunning || !hasSelection || selectedIndex == 0);
        moveDownButton.setDisable(
                launchOperationRunning || !hasSelection || selectedIndex >= itemCount - 1);
        launchAllButton.setDisable(launchOperationRunning || !hasItems);
        clearButton.setDisable(launchOperationRunning || !hasItems);
        refreshStatusButton.setDisable(launchOperationRunning || !hasItems);
        long runningCount = appListView.getItems().stream()
                .map(AppInfo::getAppPath)
                .map(processStatusCache::get)
                .filter(Objects::nonNull)
                .filter(AppProcessStatus::isRunning)
                .count();
        appCountLabel.setText(runningCount + " 运行中 · " + itemCount + " 个");
        appCountLabel.getStyleClass().removeAll("status-online", "status-offline");
        appCountLabel.getStyleClass().add(runningCount > 0 ? "status-online" : "status-offline");
    }

    private void setLaunchOperationRunning(boolean running) {
        Runnable update = () -> {
            launchOperationRunning = running;
            updateActionButtonStates();
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }
}
