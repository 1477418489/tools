package plugin.javafxtools.service;

import javafx.application.Platform;

/**
 * 启动项工具的列表刷新调度与重复刷新保护。
 *
 * @author wwj
 */
public class AppLauncherUiRefreshService {
    private final Runnable refreshListView;
    private final Object uiUpdateLock = new Object();
    private volatile boolean uiUpdatePending = false;

    /**
     * 创建 UI 刷新调度服务。
     *
     * @param refreshListView 列表刷新回调
     */
    public AppLauncherUiRefreshService(Runnable refreshListView) {
        this.refreshListView = refreshListView;
    }

    /**
     * 调度一次 JavaFX 线程上的列表刷新，避免同一时间积压多次刷新。
     */
    public void scheduleUpdate() {
        synchronized (uiUpdateLock) {
            if (uiUpdatePending) {
                return;
            }
            uiUpdatePending = true;
        }

        Platform.runLater(() -> {
            try {
                refreshListView.run();
            } finally {
                finishUpdate();
            }
        });
    }

    /**
     * 尝试标记手动刷新开始。
     *
     * @return true 表示可以继续刷新
     */
    public boolean tryStartUpdate() {
        synchronized (uiUpdateLock) {
            if (uiUpdatePending) {
                return false;
            }
            uiUpdatePending = true;
            return true;
        }
    }

    /**
     * 完成当前刷新标记。
     */
    public void finishUpdate() {
        synchronized (uiUpdateLock) {
            uiUpdatePending = false;
        }
    }

    /**
     * 重置刷新状态。
     */
    public void reset() {
        finishUpdate();
    }
}
