package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.FxTheme;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 域名保活管理页签的状态栏、进度和提示弹窗辅助类。
 *
 * @author wwj
 */
public class KeepAliveUiSupport {
    private static final DateTimeFormatter UPDATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Button addButton;
    private final Button updateButton;
    private final Button removeButton;
    private final Button saveButton;
    private final ProgressIndicator progressIndicator;
    private final Label configCountLabel;
    private final Label statusLabel;
    private final Label activeCountLabel;
    private final Label lastUpdateLabel;

    private boolean controlsDisabled;

    private boolean selectionAvailable;

    /**
     * 创建保活页签 UI 辅助类。
     *
     * @param addButton 添加按钮
     * @param updateButton 更新按钮
     * @param removeButton 删除按钮
     * @param saveButton 保存按钮
     * @param progressIndicator 后台进度指示器
     * @param configCountLabel 配置数量标签
     * @param statusLabel 服务状态标签
     * @param activeCountLabel 活跃数量标签
     * @param lastUpdateLabel 最后更新时间标签
     */
    public KeepAliveUiSupport(Button addButton,
                              Button updateButton,
                              Button removeButton,
                              Button saveButton,
                              ProgressIndicator progressIndicator,
                              Label configCountLabel,
                              Label statusLabel,
                              Label activeCountLabel,
                              Label lastUpdateLabel) {
        this.addButton = addButton;
        this.updateButton = updateButton;
        this.removeButton = removeButton;
        this.saveButton = saveButton;
        this.progressIndicator = progressIndicator;
        this.configCountLabel = configCountLabel;
        this.statusLabel = statusLabel;
        this.activeCountLabel = activeCountLabel;
        this.lastUpdateLabel = lastUpdateLabel;
    }

    /**
     * 初始化按钮、进度条和日志区域状态。
     *
     * @param logArea 日志输出区域
     */
    public void setupInitialUi(TextArea logArea) {
        showProgress(false);
        setButtonsDisabled(true);
        if (logArea != null) {
            logArea.setText("正在初始化...\n");
        }
    }

    /**
     * 更新状态栏中的配置数量、活跃数量和最近更新时间。
     *
     * @param configCount 配置数量
     * @param activeCount 活跃域名数量
     */
    public void updateStatus(int configCount, int activeCount) {
        if (configCountLabel != null) {
            configCountLabel.setText(configCount + " 条配置");
        }

        if (activeCountLabel != null) {
            activeCountLabel.setText("活跃: " + activeCount);
        }

        updateServiceStatus(configCount, activeCount);

        if (lastUpdateLabel != null) {
            lastUpdateLabel.setText("最后更新: " + LocalTime.now().format(UPDATE_TIME_FORMATTER));
        }
    }

    /**
     * 批量设置配置操作按钮可用状态。
     *
     * @param disabled 是否禁用
     */
    public void setButtonsDisabled(boolean disabled) {
        controlsDisabled = disabled;
        applyButtonStates();
    }

    /**
     * 根据表格是否有选中项更新修改和删除按钮。
     *
     * @param available 是否有选中的配置
     */
    public void setSelectionAvailable(boolean available) {
        selectionAvailable = available;
        applyButtonStates();
    }

    /**
     * 显示或隐藏后台操作进度。
     *
     * @param show 是否显示
     */
    public void showProgress(boolean show) {
        if (progressIndicator != null) {
            progressIndicator.setVisible(show);
            progressIndicator.setManaged(show);
        }
    }

    /**
     * 显示警告弹窗。
     *
     * @param message 警告内容
     */
    public void showWarning(String message) {
        runOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            FxTheme.apply(alert);
            alert.setTitle("警告");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    /**
     * 请求用户确认删除保活配置。
     *
     * @param domain 即将删除的地址
     * @return 用户是否确认
     */
    public boolean confirmRemoval(String domain) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除保活配置 \"" + domain + "\"？",
                ButtonType.OK, ButtonType.CANCEL);
        FxTheme.apply(alert);
        alert.setTitle("删除配置");
        alert.setHeaderText(null);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    /**
     * 确保指定任务在 JavaFX 线程执行。
     *
     * @param runnable 要执行的任务
     */
    public void runOnFxThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    private void updateServiceStatus(int configCount, int activeCount) {
        if (statusLabel == null) {
            return;
        }

        statusLabel.getStyleClass().removeAll(
                "status-text", "feedback-success", "feedback-warning");
        statusLabel.getStyleClass().add("status-text");
        if (activeCount > 0) {
            statusLabel.setText("运行中（" + activeCount + " 个域名活跃）");
            statusLabel.getStyleClass().add("feedback-success");
        } else if (configCount > 0) {
            statusLabel.setText("就绪（" + configCount + " 个配置）");
            statusLabel.getStyleClass().add("feedback-warning");
        } else {
            statusLabel.setText("就绪");
        }
    }

    private void applyButtonStates() {
        if (addButton != null) {
            addButton.setDisable(controlsDisabled);
        }
        if (updateButton != null) {
            updateButton.setDisable(controlsDisabled || !selectionAvailable);
        }
        if (removeButton != null) {
            removeButton.setDisable(controlsDisabled || !selectionAvailable);
        }
        if (saveButton != null) {
            saveButton.setDisable(controlsDisabled);
        }
    }
}
