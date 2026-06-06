package plugin.javafxtools.service;

import javafx.collections.ObservableList;
import javafx.scene.control.TableView;
import plugin.javafxtools.model.KeepAliveConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 域名保活配置的加载、增删改保存和服务同步编排。
 *
 * @author wwj
 */
public class KeepAliveActionService {
    private final TableView<KeepAliveConfig> configTableView;
    private final ObservableList<KeepAliveConfig> configList;
    private final KeepAliveConfigStore configStore;
    private final KeepAliveFormSupport formSupport;
    private final KeepAliveUiSupport uiSupport;
    private final ExecutorService backgroundExecutor;
    private final Consumer<String> infoLogger;
    private final Consumer<String> errorLogger;
    private final Consumer<String> afterOperation;
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);

    private EnhancedKeepAliveService keepAliveService;

    /**
     * 创建保活动作编排服务。
     *
     * @param configTableView 配置表格
     * @param configList 配置列表
     * @param configStore 配置存储
     * @param formSupport 表单辅助对象
     * @param uiSupport UI 辅助对象
     * @param backgroundExecutor 后台执行器
     * @param keepAliveService 保活执行服务
     * @param infoLogger 信息日志回调
     * @param errorLogger 错误日志回调
     * @param afterOperation 操作完成回调
     */
    public KeepAliveActionService(TableView<KeepAliveConfig> configTableView,
                                  ObservableList<KeepAliveConfig> configList,
                                  KeepAliveConfigStore configStore,
                                  KeepAliveFormSupport formSupport,
                                  KeepAliveUiSupport uiSupport,
                                  ExecutorService backgroundExecutor,
                                  EnhancedKeepAliveService keepAliveService,
                                  Consumer<String> infoLogger,
                                  Consumer<String> errorLogger,
                                  Consumer<String> afterOperation) {
        this.configTableView = configTableView;
        this.configList = configList;
        this.configStore = configStore;
        this.formSupport = formSupport;
        this.uiSupport = uiSupport;
        this.backgroundExecutor = backgroundExecutor;
        this.keepAliveService = keepAliveService;
        this.infoLogger = infoLogger;
        this.errorLogger = errorLogger;
        this.afterOperation = afterOperation;
    }

    /**
     * 设置当前使用的保活执行服务。
     *
     * @param keepAliveService 保活执行服务
     */
    public void setKeepAliveService(EnhancedKeepAliveService keepAliveService) {
        this.keepAliveService = keepAliveService;
    }

    /**
     * 异步加载保活配置文件。
     */
    public void loadConfigsAsync() {
        backgroundExecutor.submit(() -> {
            try {
                List<KeepAliveConfig> loadedList = configStore.loadConfigs();
                uiSupport.runOnFxThread(() -> {
                    configList.setAll(loadedList);
                    List<KeepAliveConfig> snapshot = new ArrayList<>(configList);
                    backgroundExecutor.submit(() -> updateServiceConfigs(snapshot));
                    infoLogger.accept("成功加载 " + configList.size() + " 条配置");
                    uiSupport.setButtonsDisabled(false);
                });
            } catch (Exception e) {
                uiSupport.runOnFxThread(() -> {
                    errorLogger.accept("加载配置文件失败: " + e.getMessage());
                    uiSupport.setButtonsDisabled(false);
                });
            }
        });
    }

    /**
     * 新增保活配置。
     */
    public void addConfig() {
        String domain = formSupport.getDomain();
        if (domain.isEmpty()) {
            uiSupport.showWarning("请输入域名");
            return;
        }

        if (!validateInterval() || !validateUrl(domain)) {
            return;
        }

        if (!beginUpdate()) {
            return;
        }

        KeepAliveConfig config = formSupport.readConfigFromForm();
        configList.add(config);
        List<KeepAliveConfig> snapshot = new ArrayList<>(configList);

        backgroundExecutor.submit(() -> {
            try {
                updateServiceConfigs(snapshot);
                configStore.saveConfigs(snapshot);

                uiSupport.runOnFxThread(() -> {
                    formSupport.clearInputFields();
                    afterOperation.accept("添加");
                });
            } catch (Exception e) {
                uiSupport.runOnFxThread(() -> errorLogger.accept("添加配置失败: " + e.getMessage()));
            } finally {
                isUpdating.set(false);
            }
        });
    }

    /**
     * 更新当前选中的保活配置。
     */
    public void updateConfig() {
        KeepAliveConfig selectedConfig = configTableView.getSelectionModel().getSelectedItem();
        if (selectedConfig == null) {
            uiSupport.showWarning("请选择要更新的配置");
            return;
        }

        String domain = formSupport.getDomain();
        if (domain.isEmpty()) {
            uiSupport.showWarning("域名不能为空");
            return;
        }

        if (!validateInterval() || !validateUrl(domain)) {
            return;
        }

        if (!beginUpdate()) {
            return;
        }

        KeepAliveConfig formConfig = formSupport.readConfigFromForm();
        selectedConfig.setDomain(formConfig.getDomain());
        selectedConfig.setEnabled(formConfig.isEnabled());
        selectedConfig.setMethod(formConfig.getMethod());
        selectedConfig.setMinInterval(formConfig.getMinInterval());
        selectedConfig.setMaxInterval(formConfig.getMaxInterval());
        selectedConfig.setUnit(formConfig.getUnit());

        int selectedIndex = configTableView.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            configTableView.getItems().set(selectedIndex, selectedConfig);
        }

        List<KeepAliveConfig> snapshot = new ArrayList<>(configList);
        backgroundExecutor.submit(() -> {
            try {
                updateServiceConfigs(snapshot);
                configStore.saveConfigs(snapshot);

                uiSupport.runOnFxThread(() -> {
                    infoLogger.accept("已更新配置: " + domain);
                    afterOperation.accept("修改");
                });
            } catch (Exception e) {
                uiSupport.runOnFxThread(() -> errorLogger.accept("更新配置失败: " + e.getMessage()));
            } finally {
                isUpdating.set(false);
            }
        });
    }

    /**
     * 删除当前选中的保活配置。
     */
    public void removeConfig() {
        KeepAliveConfig selectedConfig = configTableView.getSelectionModel().getSelectedItem();
        if (selectedConfig == null) {
            uiSupport.showWarning("请选择要删除的配置");
            return;
        }

        if (!beginUpdate()) {
            return;
        }

        String domain = selectedConfig.getDomain();
        configList.remove(selectedConfig);
        List<KeepAliveConfig> snapshot = new ArrayList<>(configList);

        backgroundExecutor.submit(() -> {
            try {
                updateServiceConfigs(snapshot);
                configStore.saveConfigs(snapshot);

                uiSupport.runOnFxThread(() -> {
                    infoLogger.accept("已删除配置: " + domain);
                    formSupport.clearInputFields();
                    afterOperation.accept("删除");
                });
            } catch (Exception e) {
                uiSupport.runOnFxThread(() -> errorLogger.accept("删除配置失败: " + e.getMessage()));
            } finally {
                isUpdating.set(false);
            }
        });
    }

    /**
     * 保存全部保活配置。
     */
    public void saveConfigs() {
        if (!beginUpdate()) {
            return;
        }

        uiSupport.showProgress(true);
        List<KeepAliveConfig> snapshot = new ArrayList<>(configList);

        backgroundExecutor.submit(() -> {
            try {
                updateServiceConfigs(snapshot);
                configStore.saveConfigs(snapshot);

                uiSupport.runOnFxThread(() -> {
                    uiSupport.showProgress(false);
                    infoLogger.accept("已保存所有配置");
                    uiSupport.showInfo("保存成功", "配置已成功保存到文件");
                    afterOperation.accept("保存");
                });
            } catch (Exception e) {
                uiSupport.runOnFxThread(() -> {
                    uiSupport.showProgress(false);
                    errorLogger.accept("保存失败: " + e.getMessage());
                    uiSupport.showWarning("保存失败: " + e.getMessage());
                });
            } finally {
                isUpdating.set(false);
            }
        });
    }

    private boolean validateInterval() {
        if (formSupport.getMaxInterval() >= formSupport.getMinInterval()) {
            return true;
        }
        uiSupport.showWarning("最大间隔不能小于最小间隔");
        return false;
    }

    private boolean validateUrl(String domain) {
        if (formSupport.isValidUrl(domain)) {
            return true;
        }
        uiSupport.showWarning("请输入有效的URL（以http://或https://开头）");
        return false;
    }

    private boolean beginUpdate() {
        if (isUpdating.compareAndSet(false, true)) {
            return true;
        }
        uiSupport.showWarning("请等待当前操作完成");
        return false;
    }

    private void updateServiceConfigs(List<KeepAliveConfig> configs) {
        if (keepAliveService != null) {
            keepAliveService.updateConfigs(configs);
        }
    }
}
