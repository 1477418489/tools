package plugin.javafxtools.controller;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.util.TimeUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 高度优化的应用程序启动器控制器
 * <p>
 * 主要优化点：
 * 1. 智能进程状态缓存机制，减少系统调用
 * 2. ListView虚拟化渲染优化
 * 3. 批量UI更新策略，减少重绘
 * 4. 优化的线程池管理和资源清理
 * 5. 内存泄漏防护和性能监控
 * 6. 异步I/O操作优化
 */
public class AppLauncherController extends BaseController {
    /**
     * 串行执行启动、停止等顺序敏感任务的执行器。
     */
    private final ExecutorService sequentialExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AppLauncher-Sequential");
        t.setDaemon(true);
        return t;
    });

    /**
     * 后台加载配置和刷新状态的共享执行器。
     */
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "AppLauncher-Background");
        t.setDaemon(true);
        return t;
    });

    // 记录最后选中的索引
    private volatile int lastSelectedIndex = -1;

    // 配置文件路径
    private static final String STORAGE_FILE = "userData/app_launcher_paths.json";

    /**
     * 启动器进程映射持久化文件路径。
     */
    private static final String PROCESS_MAP_FILE = "userData/process_map.json";

    // 优化的进程控制参数
    private static final long PROCESS_CHECK_DELAY_MS = 800;

    /**
     * 终止进程时等待正常退出的毫秒数。
     */
    private static final int PROCESS_TERMINATE_TIMEOUT_MS = 1500;

    /**
     * 进程状态定时检查间隔，单位毫秒。
     */
    private static final int PROCESS_CHECK_INTERVAL_MS = 1000 * 10;

    /**
     * 启动项日志最大保留行数。
     */
    private static final int MAX_LOG_LINES = 800;

    /**
     * 防止状态检查任务重入的标志。
     */
    private volatile boolean statusCheckInProgress = false;

    /**
     * 状态检查任务互斥锁。
     */
    private final Object statusCheckLock = new Object();

    // FXML注入的UI组件
    @FXML
    private TextField appPathField;

    /**
     * 应用启动项列表。
     */
    @FXML
    private ListView<AppInfo> appListView;

    /**
     * 启动项页签中的操作按钮组。
     */
    @FXML
    private Button browseButton, addButton, launchSingleButton, launchAllButton,
            killProcessButton, removeButton, clearButton, refreshStatusButton;

    /**
     * 启动项模块日志输出区域。
     */
    @FXML
    private TextArea logArea;

    // 数据存储 - 使用线程安全的集合
    private final List<AppInfo> appInfos = Collections.synchronizedList(new ArrayList<>());

    /**
     * 启动器记录的应用路径与进程标识映射。
     */
    private volatile Map<String, String> launcherProcessMap = new ConcurrentHashMap<>();

    /**
     * 主舞台引用，用于文件选择等窗口交互。
     */
    private volatile Stage primaryStage;

    /**
     * 进程状态跟踪器。
     */
    private final ProcessTracker processTracker = new ProcessTracker();

    // 优化的状态检查执行器
    private final ScheduledExecutorService statusCheckExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AppLauncher-StatusCheck");
                t.setDaemon(true);
                return t;
            });

    // 智能进程状态缓存
    private final Map<String, ProcessStatus> processStatusCache = new ConcurrentHashMap<>();

    // UI更新批处理控制
    private volatile boolean uiUpdatePending = false;

    /**
     * UI 批量刷新互斥锁。
     */
    private final Object uiUpdateLock = new Object();

    // 性能监控
    private volatile long lastStatusCheckTime = 0;

    /**
     * 已执行的状态检查次数。
     */
    private volatile int statusCheckCount = 0;

    /**
     * 进程状态缓存项 - 包含状态和时间戳
     */
    private static class ProcessStatus {
        /**
         * 最近一次检查到的运行状态。
         */
        final boolean isRunning;

        /**
         * 缓存创建时间戳。
         */
        final long timestamp;

        /**
         * 进程检查耗时。
         */
        final long checkDuration;

        /**
         * 创建进程状态缓存项。
         *
         * @param isRunning 是否运行中
         * @param checkDuration 检查耗时
         */
        ProcessStatus(boolean isRunning, long checkDuration) {
            this.isRunning = isRunning;
            this.timestamp = System.currentTimeMillis();
            this.checkDuration = checkDuration;
        }

        /**
         * 判断缓存项是否过期。
         *
         * @param maxAge 最大缓存时长
         * @return 是否过期
         */
        boolean isExpired(long maxAge) {
            return System.currentTimeMillis() - timestamp > maxAge;
        }
    }

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
    public void cleanup() {
        info("开始清理资源...");

        // 设置清理标志，停止新的任务提交
        synchronized (uiUpdateLock) {
            uiUpdatePending = false;
        }

        // 注意：不再强制终止所有进程，让独立启动的进程继续运行
        info("保留独立启动的进程继续运行，仅清理托管进程");
        try {
            processTracker.cleanupManagedProcessesOnly();
        } catch (Exception e) {
            error("清理托管进程时出错: " + e.getMessage());
        }

        // 清理数据
        appInfos.clear();
        processStatusCache.clear();
        launcherProcessMap.clear();

        // 优化的执行器关闭顺序
        shutdownExecutorGracefully("StatusCheck", statusCheckExecutor, 3);
        shutdownExecutorGracefully("Sequential", sequentialExecutor, 3);
        shutdownExecutorGracefully("Background", backgroundExecutor, 5);

        info("资源清理完成，独立进程将继续运行");
    }

    /**
     * 优雅关闭执行器
     */
    private void shutdownExecutorGracefully(String name, ExecutorService executor, int timeoutSeconds) {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        try {
            executor.shutdown();
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                debug(name + "执行器未在" + timeoutSeconds + "秒内关闭，强制关闭");
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    error(name + "执行器强制关闭失败");
                }
            } else {
                debug(name + "执行器已优雅关闭");
            }
        } catch (InterruptedException e) {
            error(name + "执行器关闭被中断");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 优化的初始化方法
     */
    @FXML
    public void initialize() {
        // 异步加载进程映射配置
        backgroundExecutor.submit(this::loadProcessMap);
        // 优化的定期状态检查任务
        statusCheckExecutor.scheduleWithFixedDelay(() -> {
            if (!appInfos.isEmpty()) {
                long startTime = System.currentTimeMillis();
                checkAllProcessStatusOptimized();
                lastStatusCheckTime = System.currentTimeMillis() - startTime;
                statusCheckCount++;
                // 每100次检查输出一次性能统计
                if (statusCheckCount % 100 == 0) {
                    debug(String.format("状态检查性能: 平均耗时 %dms, 检查次数 %d",
                            lastStatusCheckTime, statusCheckCount));
                }
            }
        }, 1000, PROCESS_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // JavaFX组件初始化
        Platform.runLater(this::initializeUI);
    }

    /**
     * 初始化UI组件
     */
    private void initializeUI() {
        try {
            // 设置UI提示文本
            appPathField.setPromptText("输入应用程序路径或点击浏览...");
            logArea.setPromptText("操作日志将显示在这里...");

            // 设置列表选择模式
            appListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

            // 高度优化的CellFactory实现
            appListView.setCellFactory(listView -> new OptimizedListCell());

            // 添加选择监听器
            appListView.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal.intValue() != -1) {
                    lastSelectedIndex = newVal.intValue();
                }
            });

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
     * 优化的ListCell实现 - 使用文字状态显示，性能更好且更直观
     */
    private class OptimizedListCell extends ListCell<AppInfo> {
        /**
         * 当前单元格复用的文本节点。
         */
        private final Text text = new Text();

        /**
         * 上一次展示文本，用于避免重复刷新。
         */
        private String lastDisplayText = "";

        {
            setGraphic(text);
        }

        /**
         * 根据应用配置和进程状态刷新单元格展示内容。
         *
         * @param item 应用配置项
         * @param empty 当前单元格是否为空
         */
        @Override
        protected void updateItem(AppInfo item, boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                if (!lastDisplayText.isEmpty()) {
                    text.setText(null);
                    lastDisplayText = "";
                }
            } else {
                // 构建包含状态的显示文本
                String baseText = item.toString();
                ProcessStatus status = processStatusCache.get(item.getAppPath());
                String statusText = (status != null && status.isRunning) ? " [运行中]" : " [未运行]";
                String displayText = baseText + statusText;

                // 只在文本变化时更新
                if (!displayText.equals(lastDisplayText)) {
                    text.setText(displayText);
                    lastDisplayText = displayText;
                }
            }
        }
    }

    /**
     * 优化的进程状态检查方法
     */
    private void checkAllProcessStatusOptimized() {
        // 防止重复执行
        synchronized (statusCheckLock) {
            if (statusCheckInProgress || appInfos.isEmpty()) {
                return;
            }
            statusCheckInProgress = true;
        }
        int currentTaskCount = getCurrentTaskCount(backgroundExecutor);
        if (currentTaskCount > 0) {
            info("当前还未完成的任务数量:" + currentTaskCount);
        }
        try {
            for (AppInfo appInfo : appInfos) {
                String procName = appInfo.getProcessName();
                String checkName = procName != null ?
                        procName : new File(appInfo.getAppPath()).getName();
                ProcessStatus cached = processStatusCache.get(appInfo.getAppPath());
                if (cached != null && !cached.isExpired(PROCESS_CHECK_INTERVAL_MS * 2)) {
                    continue;
                }
                long start = System.currentTimeMillis();
                boolean isRunning = processTracker.isProcessRunning(checkName);
                long duration = System.currentTimeMillis() - start;
                processStatusCache.put(appInfo.getAppPath(),
                        new ProcessStatus(isRunning, duration));
            }
            scheduleUIUpdate();
        } finally {
            synchronized (statusCheckLock) {
                statusCheckInProgress = false;
            }
        }
    }

    /**
     * 强制检查单个进程状态 - 用于批量启动后的状态验证
     */
    private void forceCheckSingleProcessStatus(AppInfo appInfo) {
        String procName = appInfo.getProcessName();
        String checkName = procName != null && !procName.isEmpty() ?
                procName : new File(appInfo.getAppPath()).getName();

        // 强制执行进程检查，忽略缓存
        long checkStart = System.currentTimeMillis();
        boolean isRunning = processTracker.isProcessRunning(checkName, true); // 强制检查
        long checkDuration = System.currentTimeMillis() - checkStart;

        // 更新缓存
        processStatusCache.put(appInfo.getAppPath(), new ProcessStatus(isRunning, checkDuration));

        // 立即更新UI
        Platform.runLater(() -> {
            refreshListViewOptimized();
            debug(String.format("强制检查进程状态: %s -> %s (耗时: %dms)",
                    checkName, isRunning ? "运行中" : "未运行", checkDuration));
        });
    }


    /**
     * 调度UI更新 - 批量处理以提高性能
     */
    private void scheduleUIUpdate() {
        synchronized (uiUpdateLock) {
            if (uiUpdatePending) {
                return; // 已有更新待处理
            }
            uiUpdatePending = true;
        }

        // 延迟50ms批量更新，避免频繁重绘
        Platform.runLater(() -> {
            try {
                refreshListViewOptimized();
            } finally {
                synchronized (uiUpdateLock) {
                    uiUpdatePending = false;
                }
            }
        });
    }

    /**
     * 优化的ListView刷新方法
     */
    private void refreshListViewOptimized() {
        if (appListView != null && appListView.getScene() != null) {
            // 只刷新可见项，避免重建整个列表
            appListView.refresh();
        }
    }

    /**
     * 加载进程映射配置
     */
    private void loadProcessMap() {
        File configFile = new File(PROCESS_MAP_FILE);
        if (configFile.exists()) {
            try (Reader reader = new FileReader(configFile)) {
                Map<String, String> loaded = new Gson().fromJson(
                        reader,
                        new TypeToken<Map<String, String>>() {
                        }.getType()
                );
                if (loaded != null) {
                    launcherProcessMap = new ConcurrentHashMap<>(loaded);
                }
            } catch (Exception e) {
                error("读取进程映射配置失败: " + e.getMessage());
            }
        }
    }

    /**
     * 浏览文件处理
     */
    @FXML
    private void handleBrowse() {
        if (primaryStage == null) {
            error("主舞台未初始化，无法打开文件选择器");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择可执行文件");
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));

        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("可执行文件", "*.exe", "*.bat", "*.cmd"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        File selectedFile = fileChooser.showOpenDialog(primaryStage);
        if (selectedFile != null) {
            appPathField.setText(selectedFile.getAbsolutePath());
            info("已选择文件: " + selectedFile.getAbsolutePath());
        }
    }

    /**
     * 添加应用处理
     */
    @FXML
    private void handleAdd() {
        String appPath = appPathField.getText().trim();
        if (appPath.isEmpty()) {
            error("请输入或选择应用程序路径");
            return;
        }

        File file = new File(appPath);
        if (!file.exists()) {
            error("指定路径不存在: " + appPath);
            return;
        }

        // 检查是否已存在
        for (AppInfo ai : appInfos) {
            if (ai.getAppPath().equals(appPath)) {
                info("应用程序已存在: " + appPath);
                return;
            }
        }

        // 自动填充进程名（优先查配置文件），允许用户修改
        String launcherName = file.getName().toLowerCase();
        String defaultProcessName = launcherProcessMap.getOrDefault(launcherName, launcherName);

        // 创建输入对话框
        TextInputDialog dialog = new TextInputDialog(defaultProcessName);
        dialog.setTitle("设置检测进程名");
        dialog.setHeaderText("请输入检测进程名（通常为实际进程名）");
        dialog.setContentText("进程名：");

        // 显示对话框并获取结果
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            info("用户取消了添加操作");
            return;
        }
        String processName = result.get().isEmpty() ? defaultProcessName : result.get();

        // 添加新应用到列表
        appInfos.add(new AppInfo(appPath, processName));
        updateAppList();
        saveAppInfos();
        info("已添加应用程序: " + appPath + " [检测进程名: " + processName + "]");
        appPathField.clear();
    }

    /**
     * 启动单个应用 - 优化状态更新
     */
    @FXML
    private void handleLaunchSingle() {
        int selectedIndex = appListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            AppInfo appInfo = appInfos.get(selectedIndex);
            // 在后台线程执行启动操作
            backgroundExecutor.submit(() -> {
                restartApplication(appInfo);
                // 启动后清理缓存并强制检查状态
                String procName = appInfo.getProcessName();
                String checkName = procName != null && !procName.isEmpty() ?
                        procName : new File(appInfo.getAppPath()).getName();
                processTracker.clearProcessCache(checkName.toLowerCase());
                // 延迟检查状态以确保进程完全启动
                try {
                    Thread.sleep(1500); // 等待进程启动
                    forceCheckSingleProcessStatus(appInfo);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } else {
            error("请先选择要启动的应用程序");
        }
    }

    /**
     * 彻底修复的批量启动方法 - 解决进程状态显示和内存问题
     */
    @FXML
    private void handleLaunchAll() {
        if (appInfos.isEmpty()) {
            error("应用程序列表为空");
            return;
        }
        List<AppInfo> appsToLaunch = new ArrayList<>(appInfos);
        info("开始批量启动 " + appsToLaunch.size() + " 个应用程序...");
        // 先清空所有状态缓存
        processStatusCache.clear();
        processTracker.clearAllProcessCache();
        backgroundExecutor.submit(() -> {
            int successCount = 0;
            int totalCount = appsToLaunch.size();
            List<AppInfo> launchedApps = new ArrayList<>();
            for (int i = 0; i < totalCount; i++) {
                AppInfo ai = appsToLaunch.get(i);
                final int currentIndex = i + 1;
                Platform.runLater(() -> info(String.format("启动进度 %d/%d: %s",
                        currentIndex, totalCount, ai.getAppPath())));
                try {
                    restartApplication(ai);
                    successCount++;
                    launchedApps.add(ai);
                    long delay = calculateLaunchDelay(ai.getAppPath());
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Platform.runLater(() -> error("批量启动被中断"));
                    break;
                } catch (Exception e) {
                    Platform.runLater(() -> error("启动失败: " + ai.getAppPath() + " - " + e.getMessage()));
                }
            }
            final int finalSuccessCount = successCount;
            Platform.runLater(() -> {
                info(String.format("批量启动完成: 成功 %d/%d", finalSuccessCount, totalCount));
                // 批量启动完成后，分批验证进程状态
                verifyBatchLaunchStatus(launchedApps);
            });
        });

    }

    /**
     * 分批验证启动状态 - 避免内存问题和提高准确性
     */
    private void verifyBatchLaunchStatus(List<AppInfo> launchedApps) {
        if (launchedApps.isEmpty()) return;
        backgroundExecutor.submit(() -> {
            try {
                info("开始验证批量启动的进程状态...");
                // 分批处理，每批最多3个，避免内存占用过大
                int verifiedCount = 0;
                for (AppInfo ai : launchedApps) {
                    // 处理当前
                    String procName = ai.getProcessName();
                    String checkName = procName != null && !procName.isEmpty() ?
                            procName : new File(ai.getAppPath()).getName();
                    // 强制检查进程状态，不使用缓存
                    long checkStart = System.currentTimeMillis();
                    boolean isRunning = processTracker.isProcessRunning(checkName, true);
                    long checkDuration = System.currentTimeMillis() - checkStart;
                    processStatusCache.put(ai.getAppPath(),
                            new ProcessStatus(isRunning, checkDuration));
                    verifiedCount++;
                    debug(String.format("验证进度 %d/%d: %s -> %s",
                            verifiedCount, launchedApps.size(), checkName,
                            isRunning ? "运行中" : "未运行"));
                    // 每批次后立即更新UI
                    Platform.runLater(this::refreshListViewOptimized);
                    // 批次间短暂延迟，避免系统负载过高
                    Thread.sleep(1000);
                }
                // 最后再次更新UI确保显示正确
                Platform.runLater(() -> {
                    refreshListViewOptimized();
                    info("批量启动状态验证完成，所有进程状态已更新");
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Platform.runLater(() -> error("状态验证被中断"));
            }
        });
    }

    /**
     * 计算启动延迟
     */
    private long calculateLaunchDelay(String appPath) {
        String lowerPath = appPath.toLowerCase();
        if (lowerPath.endsWith(".exe")) {
            return 5000; // exe文件需要更长启动时间
        } else if (lowerPath.endsWith(".bat") || lowerPath.endsWith(".cmd")) {
            return 3000; // 批处理文件
        } else {
            return 8000; // 其他文件
        }
    }

    /**
     * 结束进程处理
     */
    @FXML
    private void handleKillProcess() {
        int selectedIndex = appListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            AppInfo ai = appInfos.get(selectedIndex);
            String procName = ai.getProcessName();
            String checkName = procName != null && !procName.isEmpty() ?
                    procName : new File(ai.getAppPath()).getName();
            info("正在尝试结束进程: " + checkName + " (" + ai.getAppPath() + ")");
            killProcessButton.setDisable(true);
            backgroundExecutor.submit(() -> {
                boolean killed = processTracker.killProcess(checkName);
                Platform.runLater(() -> {
                    killProcessButton.setDisable(false);
                    if (killed) {
                        info("成功结束进程: " + ai.getAppPath());
                        updateAppList();
                    } else {
                        info("未找到运行中的进程: " + ai.getAppPath());
                    }
                });
            });
        } else {
            error("请先选择要结束的应用程序");
        }
    }

    /**
     * 移除应用处理
     */
    @FXML
    private void handleRemove() {
        int selectedIndex = appListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            AppInfo removed = appInfos.get(selectedIndex);

            // 确认对话框
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("确认移除");
            alert.setHeaderText("确认要移除所选应用程序吗？");
            alert.setContentText("[" + removed.getAppPath() + "] 会被移除，相关进程将被终止。是否继续？");

            Optional<ButtonType> result = alert.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                info("用户取消了移除操作");
                return;
            }

            String procName = removed.getProcessName();
            String checkName = procName != null && !procName.isEmpty() ?
                    procName : new File(removed.getAppPath()).getName();

            // 先更新界面，再在后台终止进程，避免系统命令阻塞 JavaFX 线程。
            appInfos.remove(selectedIndex);
            updateAppList();
            info("已从列表移除: " + removed.getAppPath());
            saveAppInfosAsync();

            backgroundExecutor.submit(() -> {
                if (processTracker.killProcess(checkName)) {
                    info("已停止并移除: " + removed.getAppPath());
                }
            });
        } else {
            error("请先选择要移除的应用程序");
        }
    }

    /**
     * 上移应用处理
     */
    @FXML
    private void handleMoveUp() {
        int selectedIndex = appListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex == -1 && lastSelectedIndex != -1) {
            selectedIndex = lastSelectedIndex;
            appListView.getSelectionModel().select(selectedIndex);
        }

        if (selectedIndex > 0) {
            // 交换位置
            Collections.swap(appInfos, selectedIndex, selectedIndex - 1);

            // 更新列表
            updateAppList();
            saveAppInfosAsync();

            // 更新选中状态
            final int newSelectedIndex = selectedIndex - 1;
            Platform.runLater(() -> {
                appListView.getSelectionModel().select(newSelectedIndex);
                appListView.requestFocus();
                lastSelectedIndex = newSelectedIndex;
            });

            info("已将应用程序上移: " + appInfos.get(selectedIndex - 1).getAppPath());
        } else if (selectedIndex == 0) {
            info("已经是第一个，无法上移");
        } else {
            error("请先选择要移动的应用程序");
        }
    }

    /**
     * 下移应用处理
     */
    @FXML
    private void handleMoveDown() {
        int selectedIndex = appListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex == -1 && lastSelectedIndex != -1) {
            selectedIndex = lastSelectedIndex;
            appListView.getSelectionModel().select(selectedIndex);
        }

        if (selectedIndex >= 0 && selectedIndex < appInfos.size() - 1) {
            // 交换位置
            Collections.swap(appInfos, selectedIndex, selectedIndex + 1);

            // 更新列表
            updateAppList();
            saveAppInfosAsync();

            // 更新选中状态
            final int newSelectedIndex = selectedIndex + 1;
            Platform.runLater(() -> {
                appListView.getSelectionModel().select(newSelectedIndex);
                appListView.requestFocus();
                lastSelectedIndex = newSelectedIndex;
            });

            info("已将应用程序下移: " + appInfos.get(newSelectedIndex).getAppPath());
        } else if (selectedIndex == appInfos.size() - 1) {
            info("已经是最后一个，无法下移");
        } else {
            error("请先选择要移动的应用程序");
        }
    }

    /**
     * 清空列表处理
     */
    @FXML
    private void handleClear() {
        // 确认对话框
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认清空");
        alert.setHeaderText("确认要清除所有应用程序路径吗？");
        alert.setContentText("此操作将终止所有已启动的进程并清空列表，是否继续？");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            info("用户取消了清空操作");
            return;
        }

        List<AppInfo> appsToKill = new ArrayList<>(appInfos);

        // 先清空界面数据，再在后台终止进程，避免批量 taskkill 卡住界面。
        appInfos.clear();
        updateAppList();
        saveAppInfosAsync();
        info("已清除所有应用程序路径");

        // 终止所有进程
        backgroundExecutor.submit(() -> {
            boolean anyProcessKilled = appsToKill.stream()
                .map(ai -> {
                    String procName = ai.getProcessName();
                    return processTracker.killProcess(
                            procName != null && !procName.isEmpty() ?
                                    procName : new File(ai.getAppPath()).getName()
                    );
                })
                .reduce(false, Boolean::logicalOr);

            if (!anyProcessKilled) {
                info("没有正在运行的进程");
            }
        });
    }


    /**
     * 优化的手动刷新进程状态 - 解决内存占用问题
     */
    @FXML
    private void handleRefreshStatus() {
        info("手动刷新进程状态...");
        // 防止重复刷新
        if (uiUpdatePending) {
            info("刷新操作正在进行中，请稍候...");
            return;
        }
        uiUpdatePending = true;
        backgroundExecutor.submit(() -> {
            try {
                lightweightStatusCheck();
            } finally {
                uiUpdatePending = false;
            }
        });
    }

    /**
     * 轻量级状态检查 - 避免内存占用过大
     */
    private void lightweightStatusCheck() {
        List<AppInfo> appsToCheck = new ArrayList<>(appInfos);
        if (appsToCheck.isEmpty()) {
            Platform.runLater(() -> info("没有应用程序需要检查"));
            return;
        }

        info("开始轻量级状态检查，共 " + appsToCheck.size() + " 个应用...");

        // 分批处理，每批最多5个
        int batchSize = 5;
        int checkedCount = 0;

        for (int i = 0; i < appsToCheck.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, appsToCheck.size());
            List<AppInfo> batch = appsToCheck.subList(i, endIndex);

            for (AppInfo appInfo : batch) {
                String procName = appInfo.getProcessName();
                String checkName = procName != null && !procName.isEmpty() ?
                        procName : new File(appInfo.getAppPath()).getName();

                // 执行轻量级检查
                long checkStart = System.currentTimeMillis();
                boolean isRunning = processTracker.isProcessRunning(checkName, true);
                long checkDuration = System.currentTimeMillis() - checkStart;
                processStatusCache.put(appInfo.getAppPath(),
                        new ProcessStatus(isRunning, checkDuration));

                checkedCount++;
            }

            // 每批次后更新UI
            int finalCheckedCount = checkedCount;
            Platform.runLater(() -> {
                refreshListViewOptimized();
                debug(String.format("状态检查进度: %d/%d", finalCheckedCount, appsToCheck.size()));
            });

            // 批次间延迟，避免系统负载过高
            if (endIndex < appsToCheck.size()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Platform.runLater(() -> error("状态检查被中断"));
                    return;
                }
            }
        }

        Platform.runLater(() -> info("手动状态检查完成"));
    }

    /**
     * 重启应用程序
     */
    private void restartApplication(AppInfo appInfo) {
        String path = appInfo.getAppPath();
        String procName = appInfo.getProcessName();
        String checkName = procName != null && !procName.isEmpty() ?
                procName : new File(path).getName();
        // 检查并终止运行中的进程
        if (processTracker.isProcessRunning(checkName)) {
            info("正在停止运行中的进程: " + path);
            if (processTracker.killProcess(checkName)) {
                info("成功停止进程: " + path);
                try {
                    Thread.sleep(PROCESS_CHECK_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                error("停止进程失败: " + path);
                return;
            }
        }
        // 启动新进程
        launchApplication(appInfo);
    }

    /**
     * 启动应用程序
     */
    private void launchApplication(AppInfo appInfo) {
        String path = appInfo.getAppPath();
        try {
            Process process = processTracker.startProcess(path);
            if (process != null) {
                info("成功启动: " + path);
                Platform.runLater(this::updateAppList);
                info("UI更新完成");
            }
        } catch (IOException e) {
            error("启动失败: " + path + " - " + e.getMessage());
        }
    }


    /**
     * 高度优化的应用列表更新方法
     */
    private void updateAppList() {
        final int selectedIndex = appListView.getSelectionModel().getSelectedIndex();

        if (Platform.isFxApplicationThread()) {
            updateAppListInternal(selectedIndex);
        } else {
            Platform.runLater(() -> updateAppListInternal(selectedIndex));
        }
    }

    /**
     * 内部列表更新逻辑
     */
    private void updateAppListInternal(int selectedIndex) {
        if (appListView == null) return;

        try {
            List<AppInfo> currentItems = appListView.getItems();
            List<AppInfo> newItems = new ArrayList<>(appInfos);

            // 智能更新策略
            if (currentItems.size() != newItems.size()) {
                // 大小不同时使用setAll，但先检查是否真的需要
                if (!currentItems.equals(newItems)) {
                    appListView.getItems().setAll(newItems);
                }
            } else {
                // 大小相同时进行精确比较和更新
                boolean needsUpdate = false;
                for (int i = 0; i < newItems.size(); i++) {
                    if (i >= currentItems.size() || !Objects.equals(currentItems.get(i), newItems.get(i))) {
                        currentItems.set(i, newItems.get(i));
                        needsUpdate = true;
                    }
                }

                // 如果有更新，触发刷新
                if (needsUpdate) {
                    appListView.refresh();
                }
            }

            // 智能选中状态恢复
            restoreSelection(selectedIndex);

        } catch (Exception e) {
            error("更新应用列表时出错: " + e.getMessage());
        }
    }

    /**
     * 恢复选中状态
     */
    private void restoreSelection(int selectedIndex) {
        try {
            if (selectedIndex >= 0 && selectedIndex < appInfos.size()) {
                appListView.getSelectionModel().select(selectedIndex);
            } else if (lastSelectedIndex >= 0 && lastSelectedIndex < appInfos.size()) {
                appListView.getSelectionModel().select(lastSelectedIndex);
            }
        } catch (Exception e) {
            debug("恢复选中状态失败: " + e.getMessage());
        }
    }

    /**
     * 加载应用信息
     */
    private void loadAppInfos() {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) {
            return;
        }

        try {
            String fileContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            List<AppInfo> savedInfos = new Gson().fromJson(
                    fileContent,
                    new TypeToken<List<AppInfo>>() {
                    }.getType()
            );

            if (savedInfos == null || savedInfos.isEmpty()) {
                // 兼容旧版本格式
                List<String> savedPaths = new Gson().fromJson(
                        fileContent,
                        new TypeToken<List<String>>() {
                        }.getType()
                );

                if (savedPaths != null) {
                    appInfos.clear();
                    for (String path : savedPaths) {
                        String launcherName = new File(path).getName().toLowerCase();
                        String processName = launcherProcessMap.getOrDefault(launcherName, launcherName);
                        appInfos.add(new AppInfo(path, processName));
                    }
                }
            } else {
                appInfos.clear();
                appInfos.addAll(savedInfos);
            }

            updateAppList();
            info("已加载 " + appInfos.size() + " 个应用程序路径");
        } catch (Exception e) {
            error("加载路径失败: " + e.getMessage());
        }
    }

    /**
     * 异步保存应用信息
     */
    private void saveAppInfosAsync() {
        backgroundExecutor.submit(() -> {
            try (Writer writer = new FileWriter(STORAGE_FILE)) {
                new Gson().toJson(appInfos, writer);
            } catch (Exception e) {
                error("保存路径失败: " + e.getMessage());
            }
        });
    }

    /**
     * 保存应用信息
     */
    private void saveAppInfos() {
        try (Writer writer = new FileWriter(STORAGE_FILE)) {
            new Gson().toJson(appInfos, writer);
        } catch (Exception e) {
            error("保存路径失败: " + e.getMessage());
        }
    }

    /**
     * 优化的日志记录方法
     */
    @Override
    public void log(String level, String message) {
        if (message == null || message.trim().isEmpty()) {
            return; // 忽略空消息
        }

        String formattedMessage = String.format("\n[%s][%s] %s",
                TimeUtils.getCurrentDateTime(), level, message);

        if (Platform.isFxApplicationThread()) {
            appendToLogAreaOptimized(formattedMessage);
        } else {
            Platform.runLater(() -> appendToLogAreaOptimized(formattedMessage));
        }
    }

    /**
     * 优化的日志追加方法
     */
    private void appendToLogAreaOptimized(String message) {
        if (logArea == null || logArea.getScene() == null) {
            return;
        }

        try {
            // 更严格的日志行数限制以防止内存泄漏
            int paragraphCount = logArea.getParagraphs().size();
            if (paragraphCount > MAX_LOG_LINES) {
                // 批量删除多行以提高性能
                int linesToDelete = Math.min(paragraphCount - MAX_LOG_LINES + 50, paragraphCount / 2);
                String text = logArea.getText();
                int deleteIndex = 0;
                for (int i = 0; i < linesToDelete; i++) {
                    int nextNewline = text.indexOf('\n', deleteIndex);
                    if (nextNewline == -1) break;
                    deleteIndex = nextNewline + 1;
                }
                if (deleteIndex > 0) {
                    logArea.deleteText(0, deleteIndex);
                }
            }

            logArea.appendText(message);

            // 优化滚动到底部的性能
            Platform.runLater(() -> {
                if (logArea.getScene() != null) {
                    logArea.setScrollTop(Double.MAX_VALUE);
                }
            });

        } catch (Exception e) {
            // 静默处理日志错误，避免递归
        }
    }

    /**
     * 高度优化的进程跟踪器内部类
     */
    private class ProcessTracker {
        /**
         * 当前控制器直接管理的进程集合。
         */
        private final Map<String, Process> managedProcesses = new ConcurrentHashMap<>();

        /**
         * 最近一次进程检查时间缓存。
         */
        private final Map<String, Long> processCheckCache = new ConcurrentHashMap<>();

        /**
         * 进程检查缓存有效期。
         */
        private static final long PROCESS_CHECK_CACHE_TTL = 2000;

        /**
         * 优化的进程运行状态检查
         */
        boolean isProcessRunning(String processName) {
            return isProcessRunning(processName, false);
        }

        /**
         * 进程运行状态检查 - 支持强制检查选项
         */
        boolean isProcessRunning(String processName, boolean forceCheck) {
            if (processName == null || processName.trim().isEmpty()) {
                return false;
            }

            // 首先检查托管进程
            if (checkManagedProcesses(processName)) {
                return true;
            }

            // 如果不是强制检查，则检查缓存
            if (!forceCheck) {
                String cacheKey = processName.toLowerCase();
                Long lastCheck = processCheckCache.get(cacheKey);
                if (lastCheck != null && (System.currentTimeMillis() - lastCheck) < PROCESS_CHECK_CACHE_TTL) {
                    return false; // 缓存显示不存在
                }
            }

            try {
                boolean isRunning = checkWindowsProcessOptimized(processName);

                // 更新缓存
                String cacheKey = processName.toLowerCase();
                if (!isRunning) {
                    processCheckCache.put(cacheKey, System.currentTimeMillis());
                } else {
                    processCheckCache.remove(cacheKey); // 运行中的进程不缓存
                }

                return isRunning;
            } catch (Exception e) {
                debug("进程检查错误: " + processName + " - " + e.getMessage());
                return false;
            }
        }

        /**
         * 检查托管进程
         */
        private boolean checkManagedProcesses(String processName) {
            return managedProcesses.entrySet().parallelStream()
                    .anyMatch(entry -> {
                        String path = entry.getKey();
                        Process managed = entry.getValue();
                        return (path.endsWith(processName) ||
                                new File(path).getName().equalsIgnoreCase(processName)) &&
                                managed.isAlive();
                    });
        }

        /**
         * 清理指定进程的缓存 - 用于强制重新检查
         */
        void clearProcessCache(String processName) {
            if (processName != null) {
                processCheckCache.remove(processName.toLowerCase());
                debug("已清理进程缓存: " + processName);
            }
        }

        /**
         * 清理所有进程缓存
         */
        void clearAllProcessCache() {
            processCheckCache.clear();
            debug("已清理所有进程缓存");
        }

        /**
         * 优化的进程终止方法
         */
        boolean killProcess(String processName) {
            if (processName == null || processName.trim().isEmpty()) {
                return false;
            }

            boolean killed = false;
            // 在当前后台任务中处理托管进程，避免向同一线程池提交子任务后等待导致自锁。
            for (Map.Entry<String, Process> entry : managedProcesses.entrySet()) {
                String path = entry.getKey();
                Process managed = entry.getValue();
                if ((path.endsWith(processName) || new File(path).getName().equalsIgnoreCase(processName))
                        && managed != null && managed.isAlive()) {
                    killed |= terminateManagedProcess(path, managed);
                }
            }

            // 使用系统命令终止进程
            try {
                killed |= killWindowsProcessOptimized(processName);

                // 清理缓存
                processCheckCache.remove(processName.toLowerCase());

            } catch (Exception e) {
                error("系统命令终止进程失败: " + processName + " - " + e.getMessage());
            }

            return killed;
        }


        /**
         * 终止一个当前工具托管的进程。
         *
         * @param path 进程启动路径
         * @param process 进程对象
         * @return 是否已触发终止
         */
        private boolean terminateManagedProcess(String path, Process process) {
            try {
                process.destroy();
                if (!process.waitFor(PROCESS_TERMINATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                }
                debug("已终止托管进程: " + path);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return true;
            } catch (Exception e) {
                error("终止托管进程失败: " + path + " - " + e.getMessage());
                return false;
            } finally {
                managedProcesses.remove(path);
            }
        }


        /**
         * 仅清理托管进程 - 不影响独立启动的进程
         */
        void cleanupManagedProcessesOnly() {
            if (managedProcesses.isEmpty()) {
                info("没有托管进程需要清理");
                return;
            }
            info("开始清理 " + managedProcesses.size() + " 个托管进程...");

            // 关闭流程直接顺序清理，避免清理时继续向即将关闭的后台线程池提交任务。
            for (Map.Entry<String, Process> entry : managedProcesses.entrySet()) {
                Process process = entry.getValue();
                if (process != null && process.isAlive()) {
                    terminateManagedProcess(entry.getKey(), process);
                }
            }

            managedProcesses.clear();
            processCheckCache.clear();
            info("托管进程清理完成，独立进程继续运行");
        }

        /**
         * 优化的进程启动方法 - 确保子进程独立运行
         */
        Process startProcess(String path) throws IOException {
            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException("进程路径不能为空");
            }

            File execFile = new File(path);
            if (!execFile.exists()) {
                throw new FileNotFoundException("可执行文件不存在: " + path);
            }

            ProcessBuilder builder = createProcessBuilder(path);

            // 关键配置：确保子进程独立运行
            builder.directory(execFile.getParentFile());
            builder.redirectErrorStream(false); // 分别处理输出和错误流

            // 设置环境变量，确保子进程独立
            Map<String, String> env = builder.environment();
            // 移除可能导致子进程依赖父进程的环境变量
            env.remove("JAVA_TOOL_OPTIONS");

            try {
                // 启动进程
                Process process = builder.start();

                // 启动独立的监控线程，但不干预进程生命周期
                monitorProcessIndependently(path, process);
                debug("成功启动独立进程: " + path + " (PID: " + process.pid() + ")");
                return process;

            } catch (IOException e) {
                error("启动进程失败: " + path + " - " + e.getMessage());
                throw e;
            }
        }

        /**
         * 独立进程监控 - 不干预进程生命周期
         */
        private void monitorProcessIndependently(String path, Process process) {
            backgroundExecutor.submit(() -> {
                try {
                    info(String.format("独立进程已启动: %s (PID: %d)", path, process.pid()));
                    String procName = new File(path).getName();
                    // 多次检查进程是否启动成功（某些应用启动较慢）
                    boolean actuallyRunning = false;
                    for (int i = 0; i < 3; i++) {
                        Thread.sleep(2000);
                        actuallyRunning = isProcessRunning(procName, true);
                        if (actuallyRunning) break;
                        debug(String.format("进程检查第%d次: %s 未检测到", i + 1, procName));
                    }
                    if (actuallyRunning) {
                        info(String.format("进程启动确认成功: %s", path));
                    } else {
                        info(String.format("进程可能未成功启动: %s", path));
                    }
                    Platform.runLater(() -> scheduleUIUpdate());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    debug("进程监控被中断: " + path);
                } catch (Exception e) {
                    error("进程监控出错: " + path + " - " + e.getMessage());
                }
            });
        }

        /**
         * 创建ProcessBuilder - 优化独立进程启动
         */
        private ProcessBuilder createProcessBuilder(String path) {
            String lowerPath = path.toLowerCase();

            if (lowerPath.endsWith(".bat") || lowerPath.endsWith(".cmd")) {
                File execFile = new File(path);
                // Windows批处理文件通过 start 独立启动，避免 cmd /c 直接执行时窗口/工作目录异常。
                return new ProcessBuilder(
                        "cmd.exe",
                        "/c",
                        "start",
                        "",
                        "/D",
                        execFile.getParent(),
                        execFile.getName()
                );
            }

            return new ProcessBuilder(path);
        }


        /**
         * 优化的Windows进程检查
         */
        private boolean checkWindowsProcessOptimized(String processName) throws IOException {
            if (processName == null || processName.trim().isEmpty()) {
                return false;
            }

            // 智能处理进程名
            String searchName = processName.toLowerCase();
            if (!searchName.endsWith(".exe")) {
                searchName += ".exe";
            }

            // 使用更高效的tasklist命令
            ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c",
                    "tasklist /FI \"IMAGENAME eq " + searchName + "\" /FO CSV /NH"
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "GBK"))) { // Windows中文系统使用GBK编码

                String finalSearchName = searchName;
                return reader.lines()
                        .filter(line -> !line.trim().isEmpty())
                        .anyMatch(line -> {
                            // CSV格式：进程名在第一个字段
                            String[] fields = line.split(",");
                            if (fields.length > 0) {
                                String procName = fields[0].replace("\"", "").trim().toLowerCase();
                                return procName.equals(finalSearchName);
                            }
                            return false;
                        });
            } finally {
                try {
                    process.waitFor(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        /**
         * 优化的Windows进程终止
         */
        private boolean killWindowsProcessOptimized(String processName) throws IOException {
            if (processName == null || processName.trim().isEmpty()) {
                return false;
            }

            String targetName = processName.toLowerCase().endsWith(".exe") ?
                    processName : processName + ".exe";

            ProcessBuilder pb = new ProcessBuilder(
                    "cmd", "/c",
                    "taskkill /F /IM \"" + targetName + "\" /T"  // /T 终止子进程
            );
            pb.redirectErrorStream(true);

            Process killProcess = pb.start();

            try {
                boolean success = killProcess.waitFor(5, TimeUnit.SECONDS) &&
                        killProcess.exitValue() == 0;

                if (success) {
                    debug("成功终止Windows进程: " + targetName);
                }
                return success;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                if (killProcess.isAlive()) {
                    killProcess.destroyForcibly();
                }
            }
        }

    }

    /**
     * 获取线程池当前活跃任务数和排队任务数。
     *
     * @param threadPoolExecutor 待检查的执行器
     * @return 当前任务数，非 ThreadPoolExecutor 时返回 -1
     */
    public static int getCurrentTaskCount(ExecutorService threadPoolExecutor) {
        if (threadPoolExecutor instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) threadPoolExecutor;
            return executor.getActiveCount() + executor.getQueue().size();
        }
        return -1;
    }
}
