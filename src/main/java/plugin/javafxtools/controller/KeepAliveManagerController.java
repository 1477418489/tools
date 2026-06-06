package plugin.javafxtools.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import plugin.javafxtools.base.BaseController;
import plugin.javafxtools.model.IntervalUnit;
import plugin.javafxtools.model.KeepAliveConfig;
import plugin.javafxtools.model.KeepAliveMethod;
import plugin.javafxtools.service.EnhancedKeepAliveService;
import plugin.javafxtools.service.KeepAliveActionService;
import plugin.javafxtools.service.KeepAliveConfigStore;
import plugin.javafxtools.service.KeepAliveFormSupport;
import plugin.javafxtools.service.KeepAliveUiSupport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 域名保活管理页签控制器，负责 FXML 事件入口和服务装配。
 */
public class KeepAliveManagerController extends BaseController {
    @FXML
    private TableView<KeepAliveConfig> configTableView;

    @FXML
    private TableColumn<KeepAliveConfig, String> domainColumn;

    @FXML
    private TableColumn<KeepAliveConfig, Boolean> enabledColumn;

    @FXML
    private TableColumn<KeepAliveConfig, KeepAliveMethod> methodColumn;

    @FXML
    private TableColumn<KeepAliveConfig, String> intervalColumn;

    @FXML
    private TableColumn<KeepAliveConfig, IntervalUnit> unitColumn;

    @FXML
    private TextField domainField;

    @FXML
    private CheckBox enabledCheckBox;

    @FXML
    private ComboBox<KeepAliveMethod> methodComboBox;

    @FXML
    private Spinner<Integer> minIntervalSpinner;

    @FXML
    private Spinner<Integer> maxIntervalSpinner;

    @FXML
    private ComboBox<IntervalUnit> unitComboBox;

    @FXML
    private Button addButton, updateButton, removeButton, saveButton;

    @FXML
    private TextArea logArea;

    @FXML
    private ProgressIndicator progressIndicator;

    @FXML
    private Label configCountLabel;

    @FXML
    private Label statusLabel;

    @FXML
    private Label activeCountLabel;

    @FXML
    private Label lastUpdateLabel;

    private final ObservableList<KeepAliveConfig> configList = FXCollections.observableArrayList();

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("KeepAlive-Background");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private EnhancedKeepAliveService keepAliveService;

    private KeepAliveUiSupport uiSupport;

    private KeepAliveFormSupport formSupport;

    private KeepAliveActionService actionService;

    /**
     * 初始化域名保活管理页签。
     */
    @FXML
    public void initialize() {
        keepAliveService = new EnhancedKeepAliveService(logArea);
        uiSupport = createUiSupport();
        formSupport = createFormSupport();
        actionService = new KeepAliveActionService(configTableView, configList,
                new KeepAliveConfigStore(), formSupport, uiSupport, backgroundExecutor,
                keepAliveService, this::info, this::error, this::afterOperation);

        uiSupport.setupInitialUi(logArea);
        formSupport.initialize();
        actionService.loadConfigsAsync();
        updateUIStatus();
        configList.addListener((ListChangeListener<KeepAliveConfig>) change -> updateUIStatus());
        afterOperation("初始化");
    }

    /**
     * 新增保活配置。
     */
    @FXML
    private void handleAdd() {
        actionService.addConfig();
    }

    /**
     * 更新当前选中的保活配置。
     */
    @FXML
    private void handleUpdate() {
        actionService.updateConfig();
    }

    /**
     * 删除当前选中的保活配置。
     */
    @FXML
    private void handleRemove() {
        actionService.removeConfig();
    }

    /**
     * 保存全部保活配置。
     */
    @FXML
    private void handleSave() {
        actionService.saveConfigs();
    }

    /**
     * 清空域名保活模块日志。
     */
    @FXML
    private void handleClearLogs() {
        handleClearLog();
    }

    /**
     * 获取域名保活模块日志输出区域。
     *
     * @return 日志输出区域
     */
    @Override
    public TextArea getLogArea() {
        return logArea;
    }

    /**
     * 设置外部注入的保活服务。
     *
     * @param service 保活服务实例
     */
    public void setKeepAliveService(EnhancedKeepAliveService service) {
        this.keepAliveService = service;
        if (actionService != null) {
            actionService.setKeepAliveService(service);
        }
    }

    /**
     * 获取当前配置列表。
     *
     * @return 保活配置列表
     */
    public ObservableList<KeepAliveConfig> getConfigList() {
        return configList;
    }

    /**
     * 清理资源。
     */
    public void cleanup() {
        info("正在清理保活管理器资源...");

        try {
            backgroundExecutor.shutdown();
            if (!backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                backgroundExecutor.shutdownNow();
                info("强制关闭后台执行器");
            }
        } catch (InterruptedException e) {
            backgroundExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (keepAliveService != null) {
            keepAliveService.cleanup();
        }

        configList.clear();
        runOnFxThread(() -> {
            if (configTableView != null) {
                configTableView.getItems().clear();
            }
            if (logArea != null) {
                logArea.clear();
            }
        });

        info("保活管理器资源清理完成");
    }

    private KeepAliveUiSupport createUiSupport() {
        return new KeepAliveUiSupport(addButton, updateButton, removeButton, saveButton,
                progressIndicator, configCountLabel, statusLabel, activeCountLabel, lastUpdateLabel);
    }

    private KeepAliveFormSupport createFormSupport() {
        return new KeepAliveFormSupport(configTableView, domainColumn, enabledColumn, methodColumn,
                intervalColumn, unitColumn, domainField, enabledCheckBox, methodComboBox,
                minIntervalSpinner, maxIntervalSpinner, unitComboBox, configList);
    }

    private void updateUIStatus() {
        if (uiSupport == null) {
            return;
        }
        int activeCount = keepAliveService == null ? 0 : keepAliveService.getActiveDomainCount();
        uiSupport.updateStatus(configList.size(), activeCount);
    }

    private void runOnFxThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    private void afterOperation(String operation) {
        runOnFxThread(() -> {
            updateUIStatus();
            info("操作完成: " + operation);
        });
    }
}
