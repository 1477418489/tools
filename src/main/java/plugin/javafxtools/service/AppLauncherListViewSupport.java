package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.model.AppProcessStatus;

import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * 启动项列表的渲染、刷新和选中状态恢复逻辑。
 *
 * @author wwj
 */
public class AppLauncherListViewSupport {
    private final ModuleLogger logger;
    private final ListView<AppInfo> appListView;
    private final Map<String, AppProcessStatus> processStatusCache;
    private final IntSupplier lastSelectedIndexSupplier;
    private final IntConsumer lastSelectedIndexSetter;

    /**
     * 创建启动项列表辅助对象。
     *
     * @param logger 日志输出接口
     * @param appListView 应用列表
     * @param processStatusCache 进程状态缓存
     * @param lastSelectedIndexSupplier 最后选中索引读取器
     * @param lastSelectedIndexSetter 最后选中索引写入器
     */
    public AppLauncherListViewSupport(ModuleLogger logger,
                                      ListView<AppInfo> appListView,
                                      Map<String, AppProcessStatus> processStatusCache,
                                      IntSupplier lastSelectedIndexSupplier,
                                      IntConsumer lastSelectedIndexSetter) {
        this.logger = logger;
        this.appListView = appListView;
        this.processStatusCache = processStatusCache;
        this.lastSelectedIndexSupplier = lastSelectedIndexSupplier;
        this.lastSelectedIndexSetter = lastSelectedIndexSetter;
    }

    /**
     * 初始化列表渲染和选择监听。
     */
    public void initialize() {
        appListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        appListView.setCellFactory(listView -> new OptimizedListCell());
        appListView.getSelectionModel().selectedIndexProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.intValue() != -1) {
                lastSelectedIndexSetter.accept(newVal.intValue());
            }
        });
    }

    /**
     * 获取当前选中索引。
     *
     * @return 选中索引
     */
    public int selectedIndex() {
        return appListView.getSelectionModel().getSelectedIndex();
    }

    /**
     * 选中并聚焦指定索引。
     *
     * @param selectedIndex 目标索引
     */
    public void selectAndFocus(int selectedIndex) {
        Runnable select = () -> {
            appListView.getSelectionModel().select(selectedIndex);
            appListView.requestFocus();
            lastSelectedIndexSetter.accept(selectedIndex);
        };
        if (Platform.isFxApplicationThread()) {
            select.run();
        } else {
            Platform.runLater(select);
        }
    }

    /**
     * 刷新可见列表项。
     */
    public void refresh() {
        if (appListView != null && appListView.getScene() != null) {
            appListView.refresh();
        }
    }

    /**
     * 更新列表数据并恢复选中状态。
     *
     * @param appInfos 应用快照
     */
    public void updateList(List<AppInfo> appInfos) {
        List<AppInfo> newItems = new ArrayList<>(appInfos);

        if (Platform.isFxApplicationThread()) {
            updateListInternal(newItems, selectedIndex());
        } else {
            Platform.runLater(() -> updateListInternal(newItems, selectedIndex()));
        }
    }

    private void updateListInternal(List<AppInfo> newItems, int selectedIndex) {
        if (appListView == null) {
            return;
        }

        try {
            List<AppInfo> currentItems = appListView.getItems();
            if (currentItems.size() != newItems.size()) {
                if (!currentItems.equals(newItems)) {
                    appListView.getItems().setAll(newItems);
                }
            } else {
                boolean needsUpdate = false;
                for (int i = 0; i < newItems.size(); i++) {
                    if (i >= currentItems.size() || !Objects.equals(currentItems.get(i), newItems.get(i))) {
                        currentItems.set(i, newItems.get(i));
                        needsUpdate = true;
                    }
                }

                if (needsUpdate) {
                    appListView.refresh();
                }
            }

            restoreSelection(selectedIndex, newItems.size());
        } catch (Exception e) {
            logger.error("更新应用列表时出错: " + e.getMessage());
        }
    }

    private void restoreSelection(int selectedIndex, int itemCount) {
        try {
            if (selectedIndex >= 0 && selectedIndex < itemCount) {
                appListView.getSelectionModel().select(selectedIndex);
            } else {
                int lastSelectedIndex = lastSelectedIndexSupplier.getAsInt();
                if (lastSelectedIndex >= 0 && lastSelectedIndex < itemCount) {
                    appListView.getSelectionModel().select(lastSelectedIndex);
                }
            }
        } catch (Exception e) {
            logger.debug("恢复选中状态失败: " + e.getMessage());
        }
    }

    private class OptimizedListCell extends ListCell<AppInfo> {
        private final Label titleLabel = new Label();
        private final Label pathLabel = new Label();
        private final Label statusLabel = new Label();
        private final Tooltip pathTooltip = new Tooltip();
        private final HBox content = new HBox(10);
        private String lastDisplayText = "";

        OptimizedListCell() {
            titleLabel.getStyleClass().add("list-item-title");
            pathLabel.getStyleClass().add("list-item-meta");
            statusLabel.getStyleClass().addAll("status-badge", "status-offline");

            VBox details = new VBox(2, titleLabel, pathLabel);
            details.setMinWidth(0);
            titleLabel.setMaxWidth(Double.MAX_VALUE);
            pathLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(details, Priority.ALWAYS);
            content.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().addAll(details, statusLabel);
            setPrefHeight(52);
        }

        @Override
        protected void updateItem(AppInfo item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setTooltip(null);
                lastDisplayText = "";
                return;
            }

            AppProcessStatus status = processStatusCache.get(item.getAppPath());
            boolean running = status != null && status.isRunning();
            String displayText = item + "|" + running;
            if (!displayText.equals(lastDisplayText)) {
                String fileName = new File(item.getAppPath()).getName();
                String processName = item.getProcessName();
                titleLabel.setText(processName == null || processName.isBlank()
                        ? fileName
                        : fileName + "  (" + processName + ")");
                pathLabel.setText(item.getAppPath());
                pathTooltip.setText(item.getAppPath());
                statusLabel.setText(running ? "运行中" : "未运行");
                statusLabel.getStyleClass().removeAll("status-offline", "status-online");
                statusLabel.getStyleClass().add(running ? "status-online" : "status-offline");
                lastDisplayText = displayText;
            }
            setTooltip(pathTooltip);
            setGraphic(content);
        }
    }
}
