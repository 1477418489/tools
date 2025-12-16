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
import plugin.javafxtools.service.EnhancedKeepAliveService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class KeepAliveManagerController {
    @FXML
    private TableView<KeepAliveConfig> configTableView;
    @FXML
    private TableColumn<KeepAliveConfig, String> domainColumn;
    @FXML
    private TableColumn<KeepAliveConfig, Boolean> enabledColumn;
    @FXML
    private TableColumn<KeepAliveConfig, String> intervalColumn;
    @FXML
    private TableColumn<KeepAliveConfig, KeepAliveConfig.TimeUnit> unitColumn;

    @FXML
    private TextField domainField;
    @FXML
    private CheckBox enabledCheckBox;
    @FXML
    private Spinner<Integer> minIntervalSpinner;
    @FXML
    private Spinner<Integer> maxIntervalSpinner;
    @FXML
    private ComboBox<KeepAliveConfig.TimeUnit> unitComboBox;
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

    private ObservableList<KeepAliveConfig> configList = FXCollections.observableArrayList();
    private static final String CONFIG_FILE = "userData/keepAlive.json";
    private EnhancedKeepAliveService keepAliveService;

    // 使用线程池处理后台任务
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("KeepAlive-Background");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // 防重复提交标志
    private volatile boolean isUpdating = false;

    @FXML
    public void initialize() {
        keepAliveService = new EnhancedKeepAliveService(logArea);
        // 初始化UI
        setupInitialUI();


        setupTableView();
        setupUnitComboBox();
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
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            lastUpdateLabel.setText("最后更新: " + sdf.format(new Date()));
        }
    }
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

    private void setupTableView() {
        domainColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getDomain()));

        enabledColumn.setCellValueFactory(cellData ->
                new SimpleBooleanProperty(cellData.getValue().isEnabled()));

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

    private void setupUnitComboBox() {
        unitComboBox.setItems(FXCollections.observableArrayList(
                KeepAliveConfig.TimeUnit.MINUTES,
                KeepAliveConfig.TimeUnit.HOURS,
                KeepAliveConfig.TimeUnit.DAYS
        ));
        unitComboBox.setValue(KeepAliveConfig.TimeUnit.MINUTES);
    }

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

    private void setupSelectionListener() {
        configTableView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> showConfigDetails(newValue)
        );
    }

    private void showConfigDetails(KeepAliveConfig config) {
        if (config != null) {
            // 批量更新UI，减少单独更新
            Platform.runLater(() -> {
                domainField.setText(config.getDomain());
                enabledCheckBox.setSelected(config.isEnabled());
                minIntervalSpinner.getValueFactory().setValue(config.getMinInterval());
                maxIntervalSpinner.getValueFactory().setValue(config.getMaxInterval());
                unitComboBox.setValue(config.getUnit());
            });
        }
    }

    @FXML
    private void handleAdd() {
        if (isUpdating) {
            showAlert("请等待当前操作完成");
            return;
        }

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
                minInterval,
                maxInterval,
                unitComboBox.getValue()
        );

        // 添加配置到列表（UI线程）
        configList.add(config);

        // 异步更新服务（后台线程）
        backgroundExecutor.submit(() -> {
            isUpdating = true;
            try {
                if (config.isEnabled()) {
                    keepAliveService.startDomain(domain);
                }
                // 异步保存到文件
                saveConfigsToFileAsync();

                Platform.runLater(() -> {
                    clearInputFields();
                    logInfo("已添加配置: " + domain);
                });
            } finally {
                isUpdating = false;
            }
        });
        afterOperation("添加");
    }

    @FXML
    private void handleUpdate() {
        if (isUpdating) {
            showAlert("请等待当前操作完成");
            return;
        }

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

        // 在UI线程中更新表格数据
        selectedConfig.setDomain(domain);
        selectedConfig.setEnabled(enabledCheckBox.isSelected());
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
            isUpdating = true;
            try {
                // 通知服务更新
                keepAliveService.updateConfigs(new ArrayList<>(configList));

                // 异步保存到文件
                saveConfigsToFileAsync();

                Platform.runLater(() -> {
                    logInfo("已更新配置: " + domain);
                });
            } finally {
                isUpdating = false;
            }
        });
        afterOperation("修改");
    }

    @FXML
    private void handleRemove() {
        if (isUpdating) {
            showAlert("请等待当前操作完成");
            return;
        }

        KeepAliveConfig selectedConfig = configTableView.getSelectionModel().getSelectedItem();
        if (selectedConfig != null) {
            String domain = selectedConfig.getDomain();

            // 从列表中移除（UI线程）
            configList.remove(selectedConfig);

            // 异步处理服务更新和文件保存
            backgroundExecutor.submit(() -> {
                isUpdating = true;
                try {
                    // 通知服务更新（会自动停止对应的任务）
                    keepAliveService.updateConfigs(new ArrayList<>(configList));

                    // 异步保存到文件
                    saveConfigsToFileAsync();

                    Platform.runLater(() -> {
                        logInfo("已删除配置: " + domain);
                        clearInputFields();
                    });
                } finally {
                    isUpdating = false;
                }
            });
        } else {
            showAlert("请选择要删除的配置");
        }
        afterOperation("删除");
    }

    @FXML
    private void handleSave() {
        if (isUpdating) {
            showAlert("请等待当前操作完成");
            return;
        }

        showProgress(true);

        backgroundExecutor.submit(() -> {
            isUpdating = true;
            try {
                keepAliveService.updateConfigs(new ArrayList<>(configList));
                saveConfigsToFileAsync();

                Platform.runLater(() -> {
                    showProgress(false);
                    logInfo("已保存所有配置");
                    showInfoAlert("保存成功", "配置已成功保存到文件");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showProgress(false);
                    logError("保存失败: " + e.getMessage());
                    showAlert("保存失败: " + e.getMessage());
                });
            } finally {
                isUpdating = false;
            }
        });
        afterOperation("添加");
    }

    private void loadConfigsAsync() {
        Task<List<KeepAliveConfig>> loadTask = new Task<>() {
            @Override
            protected List<KeepAliveConfig> call() throws Exception {
                return loadConfigsFromFileBackground();
            }

            @Override
            protected void succeeded() {
                List<KeepAliveConfig> loadedList = getValue();
                if (loadedList != null) {
                    configList.setAll(FXCollections.observableArrayList(loadedList));

                    // 异步更新服务（不阻塞UI）
                    backgroundExecutor.submit(() -> {
                        keepAliveService.updateConfigs(new ArrayList<>(configList));
                    });

                    Platform.runLater(() -> {
                        logInfo("成功加载 " + configList.size() + " 条配置");
                        setButtonsDisabled(false);
                    });
                }
            }

            @Override
            protected void failed() {
                Platform.runLater(() -> {
                    logError("加载配置文件失败: " + getException().getMessage());
                    setButtonsDisabled(false);
                });
            }
        };

        // 在后台线程执行加载
        new Thread(loadTask).start();
    }

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

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            return mapper.readValue(configFile, new TypeReference<List<KeepAliveConfig>>() {});

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

    private void saveConfigsToFileAsync() {
        backgroundExecutor.submit(() -> {
            try {
                saveConfigsToFileBackground();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    logError("异步保存失败: " + e.getMessage());
                });
            }
        });
    }

    private void saveConfigsToFileBackground() throws IOException {
        File configFile = new File(CONFIG_FILE);
        configFile.getParentFile().mkdirs();

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);

        List<KeepAliveConfig> listToSave = new ArrayList<>(configList);
        mapper.writeValue(configFile, listToSave);
    }

    private void saveEmptyConfigFile() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(SerializationFeature.INDENT_OUTPUT, true);

            List<KeepAliveConfig> emptyList = new ArrayList<>();
            mapper.writeValue(new File(CONFIG_FILE), emptyList);
        } catch (IOException e) {
            // 静默处理
        }
    }

    private void showProgress(boolean show) {
        if (progressIndicator != null) {
            progressIndicator.setVisible(show);
            progressIndicator.setManaged(show);
        }
    }

    private void logInfo(String message) {
        Platform.runLater(() -> {
            if (logArea != null) {
                // 简化日志追加，避免频繁滚动
                logArea.appendText("[INFO] " + message + "\n");

                // 偶尔滚动到底部
                if (Math.random() < 0.1) {
                    logArea.setScrollTop(Double.MAX_VALUE);
                }
            }
        });
    }

    private void logError(String message) {
        Platform.runLater(() -> {
            if (logArea != null) {
                logArea.appendText("[ERROR] " + message + "\n");
                logArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    private void showAlert(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("警告");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private void showInfoAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private void clearInputFields() {
        domainField.clear();
        enabledCheckBox.setSelected(false);
        minIntervalSpinner.getValueFactory().setValue(10);
        maxIntervalSpinner.getValueFactory().setValue(30);
        unitComboBox.setValue(KeepAliveConfig.TimeUnit.MINUTES);
    }

    private boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    public void setKeepAliveService(EnhancedKeepAliveService service) {
        this.keepAliveService = service;
    }

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
        Platform.runLater(() -> {
            if (configTableView != null) {
                configTableView.getItems().clear();
            }
            if (logArea != null) {
                logArea.clear();
            }
        });

        logInfo("保活管理器资源清理完成");
    }
    @FXML
    private void handleClearLogs() {
        if (logArea != null) {
            logArea.clear();
            logInfo("日志已清空");
        }
    }

    // 在所有操作完成后调用updateUIStatus()
    private void afterOperation(String operation) {
        Platform.runLater(() -> {
            updateUIStatus();
            logInfo("操作完成: " + operation);
        });
    }
}