package plugin.javafxtools.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import plugin.javafxtools.model.KeepAliveConfig;
import plugin.javafxtools.model.KeepAliveMethod;
import plugin.javafxtools.service.EnhancedKeepAliveService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import java.util.ArrayList;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import plugin.javafxtools.model.IntervalUnit;
import java.util.concurrent.TimeUnit;

import plugin.javafxtools.base.BaseController;

/**
 * 域名保活管理页签控制器，负责配置维护、持久化和保活服务联动。
 */
public class KeepAliveManagerController extends BaseController {
    /**
     * 域名保活配置文件路径。
     */
    private static final String CONFIG_FILE = "userData/keepAlive.json";

    /**
     * 域名保活配置列表反序列化类型。
     */
    private static final TypeReference<List<KeepAliveConfig>> KEEP_ALIVE_CONFIG_LIST_TYPE = new TypeReference<>() {};

    /**
     * 保活配置 JSON 读写器。
     */
    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    /**
     * 保活配置表格。
     */
    @FXML
    private TableView<KeepAliveConfig> configTableView;

    /**
     * 域名列。
     */
    @FXML
    private TableColumn<KeepAliveConfig, String> domainColumn;

    /**
     * 启用状态列。
     */
    @FXML
    private TableColumn<KeepAliveConfig, Boolean> enabledColumn;

    /**
     * 保活方式列。
     */
    @FXML
    private TableColumn<KeepAliveConfig, KeepAliveMethod> methodColumn;

    /**
     * 间隔范围列。
     */
    @FXML
    private TableColumn<KeepAliveConfig, String> intervalColumn;

    /**
     * 间隔单位列。
     */
    @FXML
    private TableColumn<KeepAliveConfig, IntervalUnit> unitColumn;

    /**
     * 域名或 URL 输入框。
     */
    @FXML
    private TextField domainField;

    /**
     * 启用状态复选框。
     */
    @FXML
    private CheckBox enabledCheckBox;

    /**
     * 保活方式选择框。
     */
    @FXML
    private ComboBox<KeepAliveMethod> methodComboBox;

    /**
     * 最小保活间隔输入器。
     */
    @FXML
    private Spinner<Integer> minIntervalSpinner;

    /**
     * 最大保活间隔输入器。
     */
    @FXML
    private Spinner<Integer> maxIntervalSpinner;

    /**
     * 间隔单位选择框。
     */
    @FXML
    private ComboBox<IntervalUnit> unitComboBox;

    /**
     * 配置增删改保存按钮。
     */
    @FXML
    private Button addButton, updateButton, removeButton, saveButton;

    /**
     * 域名保活模块日志输出区。
     */
    @FXML
    private TextArea logArea;

    /**
     * 后台操作进度指示器。
     */
    @FXML
    private ProgressIndicator progressIndicator;

    /**
     * 配置数量标签。
     */
    @FXML
    private Label configCountLabel;

    /**
     * 服务运行状态标签。
     */
    @FXML
    private Label statusLabel;

    /**
     * 当前活跃域名数量标签。
     */
    @FXML
    private Label activeCountLabel;

    /**
     * 最后更新时间标签。
     */
    @FXML
    private Label lastUpdateLabel;

    /**
     * 当前页面展示的保活配置列表。
     */
    private ObservableList<KeepAliveConfig> configList = FXCollections.observableArrayList();

    /**
     * 域名保活执行服务。
     */
    private EnhancedKeepAliveService keepAliveService;

    /**
     * 后台文件读写和服务更新执行器。
     */
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("KeepAlive-Background");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /**
     * 防止重复提交增删改保存操作的标志。
     */
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);

    /**
     * 初始化域名保活管理页签。
     */
    @FXML
    public void initialize() {
        keepAliveService = new EnhancedKeepAliveService(logArea);
        // 初始化UI
        setupInitialUI();


        setupTableView();
        setupUnitComboBox();
        setupMethodComboBox();
        setupIntervalSpinners();
        setupSelectionListener();
        // 异步加载配置（避免阻塞UI）
        loadConfigsAsync();
        updateUIStatus();
        configList.addListener((ListChangeListener<KeepAliveConfig>) change -> {
            updateUIStatus();
        });
        afterOperation("初始化");
    }

    /**
     * 更新状态栏中的配置数量、活跃数量和最近更新时间。
     */
    private void updateUIStatus() {
        if (configCountLabel != null) {
            configCountLabel.setText(configList.size() + " 条配置");
        }

        if (keepAliveService != null && activeCountLabel != null) {
            int activeCount = keepAliveService.getActiveDomainCount();
            activeCountLabel.setText("活跃: " + activeCount);

            // 更新状态标签
            if (statusLabel != null) {
                if (activeCount > 0) {
                    statusLabel.setText("运行中 (" + activeCount + "个域名活跃)");
                    statusLabel.setStyle("-fx-text-fill: #27ae60;");
                } else if (configList.size() > 0) {
                    statusLabel.setText("就绪 (" + configList.size() + "个配置)");
                    statusLabel.setStyle("-fx-text-fill: #f39c12;");
                } else {
                    statusLabel.setText("就绪");
                    statusLabel.setStyle("-fx-text-fill: #7f8c8d;");
                }
            }
        }

        if (lastUpdateLabel != null) {
            lastUpdateLabel.setText("最后更新: " + java.time.LocalTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        }
    }

    /**
     * 初始化按钮、进度条和日志区域状态。
     */
    private void setupInitialUI() {
        // 隐藏进度指示器
        if (progressIndicator != null) {
            progressIndicator.setVisible(false);
        }

        // 初始禁用按钮，防止重复点击
        setButtonsDisabled(true);

        if (logArea != null) {
            logArea.setText("正在初始化...\n");
        }
    }

    /**
     * 批量设置配置操作按钮可用状态。
     *
     * @param disabled 是否禁用
     */
    private void setButtonsDisabled(boolean disabled) {
        if (addButton != null) {
            addButton.setDisable(disabled);
        }
        if (updateButton != null) {
            updateButton.setDisable(disabled);
        }
        if (removeButton != null) {
            removeButton.setDisable(disabled);
        }
        if (saveButton != null) {
            saveButton.setDisable(disabled);
        }
    }

    /**
     * 初始化保活配置表格列绑定和选择模式。
     */
    private void setupTableView() {
        domainColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getDomain()));

        enabledColumn.setCellValueFactory(cellData ->
                new SimpleBooleanProperty(cellData.getValue().isEnabled()));

        methodColumn.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().getMethod()));

        intervalColumn.setCellValueFactory(cellData -> {
            KeepAliveConfig config = cellData.getValue();
            String intervalRange = String.format("%d-%d %s",
                    config.getMinInterval(),
                    config.getMaxInterval(),
                    config.getUnit().getDisplayName());
            return new SimpleStringProperty(intervalRange);
        });

        unitColumn.setCellValueFactory(cellData ->
                new SimpleObjectProperty<>(cellData.getValue().getUnit()));

        configTableView.setItems(configList);

        // 优化表格性能
        configTableView.setFixedCellSize(35);
        configTableView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
    }

    /**
     * 初始化间隔单位选择框。
     */
    private void setupUnitComboBox() {
        unitComboBox.setItems(FXCollections.observableArrayList(
                IntervalUnit.MINUTES,
                IntervalUnit.HOURS,
                IntervalUnit.DAYS
        ));
        unitComboBox.setValue(IntervalUnit.MINUTES);
    }

    /**
     * 初始化保活方式选择框。
     */
    private void setupMethodComboBox() {
        methodComboBox.setItems(FXCollections.observableArrayList(
                KeepAliveMethod.HTTP,
                KeepAliveMethod.PING
        ));
        methodComboBox.setValue(KeepAliveMethod.HTTP);
    }

    /**
     * 初始化最小和最大间隔输入器。
     */
    private void setupIntervalSpinners() {
        minIntervalSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 1000, 10));

        maxIntervalSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 1000, 30));

        // 简化监听器，减少UI更新
        minIntervalSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal > maxIntervalSpinner.getValue()) {
                maxIntervalSpinner.getValueFactory().setValue(newVal);
            }
        });
    }

    /**
     * 初始化表格选中项监听器。
     */
    private void setupSelectionListener() {
        configTableView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> showConfigDetails(newValue)
        );
    }

    /**
     * 将选中配置回填到表单。
     *
     * @param config 选中的保活配置
     */
    private void showConfigDetails(KeepAliveConfig config) {
        if (config != null) {
            // 批量更新UI，减少单独更新
            runOnFxThread(() -> {
                domainField.setText(config.getDomain());
                enabledCheckBox.setSelected(config.isEnabled());
                methodComboBox.setValue(config.getMethod());
                minIntervalSpinner.getValueFactory().setValue(config.getMinInterval());
                maxIntervalSpinner.getValueFactory().setValue(config.getMaxInterval());
                unitComboBox.setValue(config.getUnit());
            });
        }
    }

    /**
     * 新增保活配置。
     */
    @FXML
    private void handleAdd() {
        String domain = domainField.getText().trim();
        if (domain.isEmpty()) {
            showAlert("请输入域名");
            return;
        }

        int minInterval = minIntervalSpinner.getValue();
        int maxInterval = maxIntervalSpinner.getValue();

        if (maxInterval < minInterval) {
            showAlert("最大间隔不能小于最小间隔");
            return;
        }

        if (!isValidUrl(domain)) {
            showAlert("请输入有效的URL（以http://或https://开头）");
            return;
        }

        KeepAliveConfig config = new KeepAliveConfig(
                domain,
                enabledCheckBox.isSelected(),
                methodComboBox.getValue(),
                minInterval,
                maxInterval,
                unitComboBox.getValue()
        );

        if (!beginUpdate()) {
            return;
        }

        // 添加配置到列表（UI线程）
        configList.add(config);

        // 异步更新服务（后台线程）
        backgroundExecutor.submit(() -> {
            try {
                keepAliveService.updateConfigs(new ArrayList<>(configList));
                saveConfigsToFileBackground();

                runOnFxThread(() -> {
                    clearInputFields();
                    afterOperation("添加");
                });
            } catch (Exception e) {
                runOnFxThread(() -> logError("添加配置失败: " + e.getMessage()));
            } finally {
                isUpdating.set(false);
            }
        });
    }

    /**
     * 更新当前选中的保活配置。
     */
    @FXML
    private void handleUpdate() {
        KeepAliveConfig selectedConfig = configTableView.getSelectionModel().getSelectedItem();
        if (selectedConfig == null) {
            showAlert("请选择要更新的配置");
            return;
        }

        // 验证输入
        String domain = domainField.getText().trim();
        if (domain.isEmpty()) {
            showAlert("域名不能为空");
            return;
        }

        int minInterval = minIntervalSpinner.getValue();
        int maxInterval = maxIntervalSpinner.getValue();

        if (maxInterval < minInterval) {
            showAlert("最大间隔不能小于最小间隔");
            return;
        }

        if (!isValidUrl(domain)) {
            showAlert("请输入有效的URL（以http://或https://开头）");
            return;
        }

        if (!beginUpdate()) {
            return;
        }

        // 在UI线程中更新表格数据
        selectedConfig.setDomain(domain);
        selectedConfig.setEnabled(enabledCheckBox.isSelected());
        selectedConfig.setMethod(methodComboBox.getValue());
        selectedConfig.setMinInterval(minInterval);
        selectedConfig.setMaxInterval(maxInterval);
        selectedConfig.setUnit(unitComboBox.getValue());

        // 刷新选中的行，而不是整个表格
        int selectedIndex = configTableView.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            configTableView.getItems().set(selectedIndex, selectedConfig);
        }

        // 异步更新服务和保存文件
        backgroundExecutor.submit(() -> {
            try {
                // 通知服务更新
                keepAliveService.updateConfigs(new ArrayList<>(configList));

                saveConfigsToFileBackground();

                runOnFxThread(() -> {
                    logInfo("已更新配置: " + domain);
                    afterOperation("修改");
                });
            } catch (Exception e) {
                runOnFxThread(() -> logError("更新配置失败: " + e.getMessage()));
            } finally {
                isUpdating.set(false);
            }
        });
    }

    /**
     * 删除当前选中的保活配置。
     */
    @FXML
    private void handleRemove() {
        KeepAliveConfig selectedConfig = configTableView.getSelectionModel().getSelectedItem();
        if (selectedConfig != null) {
            String domain = selectedConfig.getDomain();

            if (!beginUpdate()) {
                return;
            }

            // 从列表中移除（UI线程）
            configList.remove(selectedConfig);

            // 异步处理服务更新和文件保存
            backgroundExecutor.submit(() -> {
                try {
                    // 通知服务更新（会自动停止对应的任务）
                    keepAliveService.updateConfigs(new ArrayList<>(configList));

                    saveConfigsToFileBackground();

                    runOnFxThread(() -> {
                        logInfo("已删除配置: " + domain);
                        clearInputFields();
                        afterOperation("删除");
                    });
                } catch (Exception e) {
                    runOnFxThread(() -> logError("删除配置失败: " + e.getMessage()));
                } finally {
                    isUpdating.set(false);
                }
            });
        } else {
            showAlert("请选择要删除的配置");
        }
    }

    /**
     * 保存全部保活配置。
     */
    @FXML
    private void handleSave() {
        if (!beginUpdate()) {
            return;
        }

        showProgress(true);

        backgroundExecutor.submit(() -> {
            try {
                keepAliveService.updateConfigs(new ArrayList<>(configList));
                saveConfigsToFileBackground();

                runOnFxThread(() -> {
                    showProgress(false);
                    logInfo("已保存所有配置");
                    showInfoAlert("保存成功", "配置已成功保存到文件");
                    afterOperation("保存");
                });
            } catch (Exception e) {
                runOnFxThread(() -> {
                    showProgress(false);
                    logError("保存失败: " + e.getMessage());
                    showAlert("保存失败: " + e.getMessage());
                });
            } finally {
                isUpdating.set(false);
            }
        });
    }

    /**
     * 异步加载保活配置文件。
     */
    private void loadConfigsAsync() {
        Task<List<KeepAliveConfig>> loadTask = new Task<>() {
            /**
             * 后台读取保活配置文件。
             *
             * @return 保活配置列表
             * @throws Exception 配置读取异常
             */
            @Override
            protected List<KeepAliveConfig> call() throws Exception {
                return loadConfigsFromFileBackground();
            }

            /**
             * 配置加载成功后刷新表格和服务。
             */
            @Override
            protected void succeeded() {
                List<KeepAliveConfig> loadedList = getValue();
                if (loadedList != null) {
                    configList.setAll(FXCollections.observableArrayList(loadedList));

                    // 异步更新服务（不阻塞UI）
                    backgroundExecutor.submit(() -> {
                        keepAliveService.updateConfigs(new ArrayList<>(configList));
                    });

                    logInfo("成功加载 " + configList.size() + " 条配置");
                    setButtonsDisabled(false);
                }
            }

            /**
             * 配置加载失败后恢复按钮状态。
             */
            @Override
            protected void failed() {
                logError("加载配置文件失败: " + getException().getMessage());
                setButtonsDisabled(false);
            }
        };

        // 在后台线程执行加载
        backgroundExecutor.submit(loadTask);
    }

    /**
     * 在后台线程读取配置文件。
     *
     * @return 保活配置列表
     */
    private List<KeepAliveConfig> loadConfigsFromFileBackground() {
        File configFile = new File(CONFIG_FILE);

        if (!configFile.exists()) {
            saveEmptyConfigFile();
            return new ArrayList<>();
        }

        try {
            if (configFile.length() == 0) {
                return new ArrayList<>();
            }

            return CONFIG_MAPPER.readValue(configFile, KEEP_ALIVE_CONFIG_LIST_TYPE);

        } catch (Exception e) {
            // 在后台线程中恢复文件，不阻塞UI
            try {
                if (configFile.exists()) {
                    String backupName = "keepAlive_backup_" + System.currentTimeMillis() + ".json";
                    File backupFile = new File(configFile.getParentFile(), backupName);
                    Files.copy(configFile.toPath(), backupFile.toPath());
                }

                saveEmptyConfigFile();
            } catch (IOException backupEx) {
                // 静默处理备份失败
            }

            throw new RuntimeException("加载配置文件失败", e);
        }
    }

    /**
     * 在后台线程保存当前配置列表。
     *
     * @throws IOException 文件写入失败时抛出
     */
    private void saveConfigsToFileBackground() throws IOException {
        File configFile = ensureConfigDirectoryExists();

        List<KeepAliveConfig> listToSave = new ArrayList<>(configList);
        CONFIG_MAPPER.writeValue(configFile, listToSave);
    }

    /**
     * 写入空配置文件。
     */
    private void saveEmptyConfigFile() {
        try {
            File configFile = ensureConfigDirectoryExists();

            List<KeepAliveConfig> emptyList = new ArrayList<>();
            CONFIG_MAPPER.writeValue(configFile, emptyList);
        } catch (IOException e) {
            // 静默处理
        }
    }

    /**
     * 确保配置文件父目录存在。
     *
     * @return 配置文件对象
     */
    private File ensureConfigDirectoryExists() {
        File configFile = new File(CONFIG_FILE);
        File parent = configFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        return configFile;
    }

    /**
     * 显示或隐藏后台操作进度。
     *
     * @param show 是否显示
     */
    private void showProgress(boolean show) {
        if (progressIndicator != null) {
            progressIndicator.setVisible(show);
            progressIndicator.setManaged(show);
        }
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
     * 记录信息日志。
     *
     * @param message 日志内容
     */
    private void logInfo(String message) {
        info(message);
    }

    /**
     * 记录错误日志。
     *
     * @param message 日志内容
     */
    private void logError(String message) {
        error(message);
    }

    /**
     * 显示警告弹窗。
     *
     * @param message 警告内容
     */
    private void showAlert(String message) {
        runOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("警告");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    /**
     * 显示信息弹窗。
     *
     * @param title 弹窗标题
     * @param message 弹窗内容
     */
    private void showInfoAlert(String title, String message) {
        runOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    /**
     * 清空配置编辑表单。
     */
    private void clearInputFields() {
        domainField.clear();
        enabledCheckBox.setSelected(false);
        methodComboBox.setValue(KeepAliveMethod.HTTP);
        minIntervalSpinner.getValueFactory().setValue(10);
        maxIntervalSpinner.getValueFactory().setValue(30);
        unitComboBox.setValue(IntervalUnit.MINUTES);
    }

    /**
     * 校验保活地址是否为 HTTP/HTTPS URL。
     *
     * @param url 待校验地址
     * @return 是否有效
     */
    private boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * 尝试进入更新状态。
     *
     * @return 是否成功进入更新状态
     */
    private boolean beginUpdate() {
        if (isUpdating.compareAndSet(false, true)) {
            return true;
        }
        showAlert("请等待当前操作完成");
        return false;
    }

    /**
     * 确保指定任务在 JavaFX 线程执行。
     *
     * @param runnable 要执行的任务
     */
    private void runOnFxThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    /**
     * 设置外部注入的保活服务。
     *
     * @param service 保活服务实例
     */
    public void setKeepAliveService(EnhancedKeepAliveService service) {
        this.keepAliveService = service;
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
     * 清理资源
     */
    public void cleanup() {
        logInfo("正在清理保活管理器资源...");

        // 1. 先停止后台执行器
        try {
            backgroundExecutor.shutdown();
            if (!backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                backgroundExecutor.shutdownNow();
                logInfo("强制关闭后台执行器");
            }
        } catch (InterruptedException e) {
            backgroundExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 2. 清理保活服务
        if (keepAliveService != null) {
            keepAliveService.cleanup();
        }

        // 3. 清空配置列表（释放内存）
        configList.clear();

        // 4. 清空UI引用
        runOnFxThread(() -> {
            if (configTableView != null) {
                configTableView.getItems().clear();
            }
            if (logArea != null) {
                logArea.clear();
            }
        });

        logInfo("保活管理器资源清理完成");
    }

    /**
     * 清空域名保活模块日志。
     */
    @FXML
    private void handleClearLogs() {
        handleClearLog();
    }

    // 在所有操作完成后调用updateUIStatus()
    private void afterOperation(String operation) {
        Platform.runLater(() -> {
            updateUIStatus();
            logInfo("操作完成: " + operation);
        });
    }
}
