package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.control.LogViewer;
import plugin.javafxtools.model.AppSettings;
import plugin.javafxtools.service.AppDataBackupService;
import plugin.javafxtools.service.AppSettingsStore;
import plugin.javafxtools.service.LoggingService;
import plugin.javafxtools.service.WindowsStartupService;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.FxTheme;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 主控制器，负责模块按需加载、应用设置和资源清理。
 */
public class MainController {
    private static final DateTimeFormatter BACKUP_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @FXML private TabPane tabPane;
    @FXML private Tab appLauncherTab;
    @FXML private Tab httpRequestTab;
    @FXML private Tab webSocketTab;
    @FXML private Tab networkToolsTab;
    @FXML private Tab networkQualityTab;
    @FXML private Tab processPortTab;
    @FXML private Tab devEnvironmentTab;
    @FXML private Tab fileAnalysisTab;
    @FXML private Tab dataFormatTab;
    @FXML private Tab strDataFormatTab;
    @FXML private Tab base64Tab;
    @FXML private Tab jarLauncherTab;
    @FXML private Tab memoReminderTab;
    @FXML private Tab keepAliveTab;
    @FXML private Tab windowsPowerTab;
    @FXML private ToggleButton appLauncherNavButton;
    @FXML private ToggleButton httpRequestNavButton;
    @FXML private ToggleButton webSocketNavButton;
    @FXML private ToggleButton networkToolsNavButton;
    @FXML private ToggleButton networkQualityNavButton;
    @FXML private ToggleButton processPortNavButton;
    @FXML private ToggleButton devEnvironmentNavButton;
    @FXML private ToggleButton fileAnalysisNavButton;
    @FXML private ToggleButton dataFormatNavButton;
    @FXML private ToggleButton strDataFormatNavButton;
    @FXML private ToggleButton base64NavButton;
    @FXML private ToggleButton jarLauncherNavButton;
    @FXML private ToggleButton memoReminderNavButton;
    @FXML private ToggleButton keepAliveNavButton;
    @FXML private ToggleButton windowsPowerNavButton;
    @FXML private Label activeSectionLabel;
    @FXML private Label activeModuleLabel;
    @FXML private CheckMenuItem closeToTrayMenuItem;
    @FXML private CheckMenuItem reminderSoundMenuItem;
    @FXML private CheckMenuItem startupMenuItem;
    @FXML private MenuItem exportBackupMenuItem;
    @FXML private LogViewer systemLogViewer;

    private final LoggingService loggingService = new LoggingService();
    private final AppSettingsStore settingsStore = new AppSettingsStore();
    private final WindowsStartupService startupService = new WindowsStartupService();
    private final AppDataBackupService backupService = new AppDataBackupService();
    private final ExecutorService settingsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FxTools-Settings");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Tab, ModuleDefinition> moduleDefinitions = new LinkedHashMap<>();
    private final Map<Tab, ControllerBinding> loadedModules = new LinkedHashMap<>();
    private final Map<ToggleButton, Tab> navigationTabs = new LinkedHashMap<>();

    private TextArea centralLogArea;
    private volatile AppSettings appSettings = AppSettings.defaults();
    private Stage primaryStage;
    private boolean applicationVisible;
    private boolean cleaned;

    @FXML
    public void initialize() {
        centralLogArea = systemLogViewer.getTextArea();
        systemLogViewer.setOnClear(loggingService::clearGlobalLogs);
        if (centralLogArea != null) {
            loggingService.addGlobalLogArea(centralLogArea);
        }
        ensureAppDataDirectoryExists();
        loadSettings();
        registerModules();
        configureNavigation();

        loadModule(memoReminderTab);
        loadModule(keepAliveTab);
        loadModule(tabPane.getSelectionModel().getSelectedItem());
        tabPane.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    loadModule(selected);
                    synchronizeNavigation(selected);
                    updateAppLauncherActivity();
                });
        loggingService.info("主控制器初始化完成");
    }

    public AppLauncherController getAppLauncherController() {
        ControllerBinding binding = loadedModules.get(appLauncherTab);
        return binding != null && binding.controller() instanceof AppLauncherController controller
                ? controller : null;
    }

    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
        AppLauncherController controller = getAppLauncherController();
        if (controller != null) {
            controller.setPrimaryStage(primaryStage);
        }
    }

    public void setApplicationVisible(boolean visible) {
        applicationVisible = visible;
        updateAppLauncherActivity();
    }

    public boolean isCloseToTrayEnabled() {
        return appSettings.closeToTray();
    }

    public void setTrayAvailable(boolean available) {
        closeToTrayMenuItem.setDisable(!available);
    }

    public TabPane getTabPane() {
        return tabPane;
    }

    @FXML
    private void handleCloseToTraySetting() {
        AppSettings candidate = new AppSettings(closeToTrayMenuItem.isSelected(),
                appSettings.reminderSoundEnabled(), appSettings.startWithWindows());
        if (!saveSettings(candidate)) {
            closeToTrayMenuItem.setSelected(appSettings.closeToTray());
        }
    }

    @FXML
    private void handleReminderSoundSetting() {
        AppSettings candidate = new AppSettings(appSettings.closeToTray(),
                reminderSoundMenuItem.isSelected(), appSettings.startWithWindows());
        if (!saveSettings(candidate)) {
            reminderSoundMenuItem.setSelected(appSettings.reminderSoundEnabled());
        }
    }

    @FXML
    private void handleStartupSetting() {
        boolean enabled = startupMenuItem.isSelected();
        boolean previousEnabled = appSettings.startWithWindows();
        startupMenuItem.setDisable(true);
        settingsExecutor.execute(() -> {
            boolean registryUpdated = false;
            try {
                startupService.setEnabled(enabled);
                registryUpdated = true;
                AppSettings candidate = new AppSettings(appSettings.closeToTray(),
                        appSettings.reminderSoundEnabled(), enabled);
                settingsStore.save(candidate);
                appSettings = candidate;
                loggingService.info(enabled ? "已启用随 Windows 登录启动" : "已关闭随 Windows 登录启动");
                Platform.runLater(() -> startupMenuItem.setDisable(false));
            } catch (IOException e) {
                if (registryUpdated) {
                    try {
                        startupService.setEnabled(previousEnabled);
                    } catch (IOException rollbackFailure) {
                        loggingService.error("恢复开机启动状态失败: "
                                + errorMessage(rollbackFailure));
                    }
                }
                loggingService.error("更新开机启动失败: " + errorMessage(e));
                Platform.runLater(() -> {
                    startupMenuItem.setSelected(appSettings.startWithWindows());
                    startupMenuItem.setDisable(!startupService.isSupported());
                    showError("开机启动设置失败", errorMessage(e));
                });
            }
        });
    }

    @FXML
    private void handleOpenDataDirectory() {
        settingsExecutor.execute(() -> {
            try {
                AppDataPaths.ensureDataDirectory();
                if (!Desktop.isDesktopSupported()) {
                    throw new IOException("当前系统不支持打开目录");
                }
                Desktop.getDesktop().open(AppDataPaths.dataDirectory().toFile());
            } catch (IOException | SecurityException e) {
                loggingService.error("打开数据目录失败: " + errorMessage(e));
                Platform.runLater(() -> showError("无法打开数据目录", errorMessage(e)));
            }
        });
    }

    @FXML
    private void handleExportDataBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出 FxTools 数据备份");
        chooser.setInitialFileName("FxTools-backup-"
                + BACKUP_TIME_FORMAT.format(LocalDateTime.now()) + ".zip");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("ZIP 备份 (*.zip)", "*.zip"));
        var target = chooser.showSaveDialog(primaryStage);
        if (target == null) {
            return;
        }

        exportBackupMenuItem.setDisable(true);
        try {
            settingsExecutor.execute(() -> exportDataBackup(target.toPath()));
        } catch (RejectedExecutionException e) {
            exportBackupMenuItem.setDisable(false);
            showError("数据备份失败", "应用正在退出，无法开始备份");
        }
    }

    private void exportDataBackup(java.nio.file.Path target) {
        try {
            AppDataPaths.ensureDataDirectory();
            AppDataBackupService.BackupResult result = backupService.export(
                    AppDataPaths.dataDirectory(), target);
            loggingService.info("数据备份已导出: " + result.archive());
            Platform.runLater(() -> {
                exportBackupMenuItem.setDisable(false);
                showInfo("数据备份完成", "已备份 " + result.fileCount()
                        + " 个文件到：\n" + result.archive());
            });
        } catch (IOException | SecurityException e) {
            loggingService.error("导出数据备份失败: " + errorMessage(e));
            Platform.runLater(() -> {
                exportBackupMenuItem.setDisable(false);
                showError("数据备份失败", errorMessage(e));
            });
        }
    }

    public void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        List<String> failures = new ArrayList<>();
        List.copyOf(loadedModules.values()).forEach(binding ->
                cleanupController(binding.name(), binding.cleanupAction(), failures));
        loadedModules.clear();
        settingsExecutor.shutdownNow();
        if (centralLogArea != null) {
            centralLogArea.clear();
        }
        if (!failures.isEmpty()) {
            loggingService.error("部分模块资源清理失败: " + String.join("、", failures));
        }
    }

    private void registerModules() {
        register(appLauncherTab, "启动与运行", "启动项", "app-launcher-view.fxml");
        register(jarLauncherTab, "启动与运行", "JAR 启动", "jar-launcher-view.fxml");
        register(windowsPowerTab, "启动与运行", "电源计划", "windows-power-view.fxml");
        register(httpRequestTab, "网络与接口", "HTTP 请求", "http-request-view.fxml");
        register(webSocketTab, "网络与接口", "WebSocket", "websocket-view.fxml");
        register(networkToolsTab, "网络与接口", "网络诊断", "network-tools-view.fxml");
        register(networkQualityTab, "网络与接口", "网络质量", "network-quality-view.fxml");
        register(keepAliveTab, "网络与接口", "域名保活", "keepalive-manager-view.fxml");
        register(processPortTab, "系统与开发", "进程与端口", "process-port-view.fxml");
        register(devEnvironmentTab, "系统与开发", "环境体检", "dev-environment-view.fxml");
        register(fileAnalysisTab, "系统与开发", "文件分析", "file-analysis-view.fxml");
        register(dataFormatTab, "数据与效率", "数据格式化", "data-format-view.fxml");
        register(strDataFormatTab, "数据与效率", "字符串处理", "strData-format-view.fxml");
        register(base64Tab, "数据与效率", "Base64 编解码", "base64-view.fxml");
        register(memoReminderTab, "数据与效率", "备忘提醒", "memo-reminder-view.fxml");
    }

    private void register(Tab tab, String section, String name, String resource) {
        moduleDefinitions.put(tab, new ModuleDefinition(section, name, resource));
    }

    private void configureNavigation() {
        navigationTabs.put(appLauncherNavButton, appLauncherTab);
        navigationTabs.put(jarLauncherNavButton, jarLauncherTab);
        navigationTabs.put(windowsPowerNavButton, windowsPowerTab);
        navigationTabs.put(httpRequestNavButton, httpRequestTab);
        navigationTabs.put(webSocketNavButton, webSocketTab);
        navigationTabs.put(networkToolsNavButton, networkToolsTab);
        navigationTabs.put(networkQualityNavButton, networkQualityTab);
        navigationTabs.put(keepAliveNavButton, keepAliveTab);
        navigationTabs.put(processPortNavButton, processPortTab);
        navigationTabs.put(devEnvironmentNavButton, devEnvironmentTab);
        navigationTabs.put(fileAnalysisNavButton, fileAnalysisTab);
        navigationTabs.put(dataFormatNavButton, dataFormatTab);
        navigationTabs.put(strDataFormatNavButton, strDataFormatTab);
        navigationTabs.put(base64NavButton, base64Tab);
        navigationTabs.put(memoReminderNavButton, memoReminderTab);

        ToggleGroup navigationGroup = new ToggleGroup();
        navigationTabs.forEach((button, tab) -> {
            button.setToggleGroup(navigationGroup);
            button.setOnAction(event -> {
                if (!button.isSelected()) {
                    button.setSelected(true);
                }
                tabPane.getSelectionModel().select(tab);
            });
        });
        synchronizeNavigation(tabPane.getSelectionModel().getSelectedItem());
    }

    private void synchronizeNavigation(Tab selectedTab) {
        navigationTabs.forEach((button, tab) -> button.setSelected(tab == selectedTab));
        ModuleDefinition definition = moduleDefinitions.get(selectedTab);
        if (definition != null) {
            activeSectionLabel.setText(definition.section());
            activeModuleLabel.setText(definition.name());
        }
    }

    private void loadModule(Tab tab) {
        if (tab == null || loadedModules.containsKey(tab)) {
            return;
        }
        ModuleDefinition definition = moduleDefinitions.get(tab);
        if (definition == null) {
            return;
        }
        try {
            URL resource = MainController.class.getResource(
                    "/plugin/javafxtools/" + definition.resource());
            if (resource == null) {
                throw new IOException("找不到模块资源: " + definition.resource());
            }
            FXMLLoader loader = new FXMLLoader(resource);
            Parent content = loader.load();
            Object controller = loader.getController();
            if (controller == null) {
                throw new IOException("FXML 未提供控制器");
            }
            tab.setContent(content);
            ControllerBinding binding = new ControllerBinding(
                    definition.name(), controller, cleanupAction(controller));
            loadedModules.put(tab, binding);
            configureLoadedController(controller);
            loggingService.info(definition.name() + "模块已加载");
        } catch (IOException | RuntimeException e) {
            tab.setContent(new Label("模块加载失败: " + errorMessage(e)));
            loggingService.error(definition.name() + "模块加载失败: " + errorMessage(e));
        }
    }

    private Runnable cleanupAction(Object controller) {
        if (controller instanceof BaseController baseController) {
            return baseController::cleanup;
        }
        if (controller instanceof JarLauncherController jarController) {
            return jarController::cleanup;
        }
        return () -> { };
    }

    private void configureLoadedController(Object controller) {
        if (controller instanceof AppLauncherController appLauncherController) {
            appLauncherController.setPrimaryStage(primaryStage);
            updateAppLauncherActivity();
        } else if (controller instanceof MemoReminderController reminderController) {
            reminderController.setReminderSoundEnabledSupplier(
                    () -> appSettings.reminderSoundEnabled());
        }
    }

    private void updateAppLauncherActivity() {
        AppLauncherController controller = getAppLauncherController();
        if (controller != null) {
            controller.setActive(applicationVisible
                    && tabPane.getSelectionModel().getSelectedItem() == appLauncherTab);
        }
    }

    private void loadSettings() {
        try {
            appSettings = settingsStore.load();
        } catch (IOException e) {
            appSettings = AppSettings.defaults();
            loggingService.error("读取应用设置失败，已使用默认设置: " + errorMessage(e));
        }
        closeToTrayMenuItem.setSelected(appSettings.closeToTray());
        reminderSoundMenuItem.setSelected(appSettings.reminderSoundEnabled());
        startupMenuItem.setSelected(appSettings.startWithWindows());
        startupMenuItem.setDisable(!startupService.isSupported());
    }

    private boolean saveSettings(AppSettings candidate) {
        try {
            settingsStore.save(candidate);
            appSettings = candidate;
            return true;
        } catch (IOException e) {
            loggingService.error("保存应用设置失败: " + errorMessage(e));
            showError("设置保存失败", errorMessage(e));
            return false;
        }
    }

    private void ensureAppDataDirectoryExists() {
        try {
            AppDataPaths.ensureDataDirectory();
        } catch (IOException | SecurityException e) {
            loggingService.error("无法创建用户数据目录: " + errorMessage(e));
        }
    }

    private void cleanupController(String name, Runnable cleanupAction, List<String> failures) {
        try {
            cleanupAction.run();
        } catch (RuntimeException e) {
            failures.add(name + " (" + errorMessage(e) + ")");
        }
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        FxTheme.apply(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
        }
        alert.show();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        FxTheme.apply(alert);
        alert.setTitle(title);
        alert.setHeaderText(null);
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
        }
        alert.show();
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private record ModuleDefinition(String section, String name, String resource) {
    }

    private record ControllerBinding(String name, Object controller, Runnable cleanupAction) {
    }
}
