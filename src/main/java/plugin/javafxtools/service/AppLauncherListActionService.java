package plugin.javafxtools.service;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.util.FxTheme;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 启动项列表的增删、排序和文件选择操作。
 *
 * @author wwj
 */
public class AppLauncherListActionService {
    private final ModuleLogger logger;
    private final List<AppInfo> appInfos;
    private final Supplier<Map<String, String>> launcherProcessMapSupplier;
    private final Runnable updateAppList;
    private final Predicate<List<AppInfo>> saveAppInfos;
    private final IntConsumer selectAndFocus;
    private final Consumer<AppInfo> killRemovedApp;
    private final Consumer<List<AppInfo>> killAllApps;

    /**
     * 创建启动项列表操作服务。
     *
     * @param logger 日志输出接口
     * @param appInfos 启动项列表
     * @param launcherProcessMapSupplier 进程映射读取器
     * @param updateAppList 列表刷新回调
     * @param saveAppInfos 配置保存回调
     * @param selectAndFocus 选中并聚焦指定索引回调
     * @param killRemovedApp 移除后终止进程回调
     * @param killAllApps 清空后终止全部进程回调
     */
    public AppLauncherListActionService(ModuleLogger logger,
                                        List<AppInfo> appInfos,
                                        Supplier<Map<String, String>> launcherProcessMapSupplier,
                                        Runnable updateAppList,
                                        Predicate<List<AppInfo>> saveAppInfos,
                                        IntConsumer selectAndFocus,
                                        Consumer<AppInfo> killRemovedApp,
                                        Consumer<List<AppInfo>> killAllApps) {
        this.logger = logger;
        this.appInfos = appInfos;
        this.launcherProcessMapSupplier = launcherProcessMapSupplier;
        this.updateAppList = updateAppList;
        this.saveAppInfos = saveAppInfos;
        this.selectAndFocus = selectAndFocus;
        this.killRemovedApp = killRemovedApp;
        this.killAllApps = killAllApps;
    }

    /**
     * 选择可执行文件并填充路径输入框。
     *
     * @param primaryStage 主舞台
     * @param appPathField 路径输入框
     */
    public void browse(Stage primaryStage, TextField appPathField) {
        if (primaryStage == null) {
            logger.error("主舞台未初始化，无法打开文件选择器");
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
            logger.info("已选择文件: " + selectedFile.getAbsolutePath());
        }
    }

    /**
     * 添加应用路径。
     *
     * @param appPathField 路径输入框
     */
    public void add(TextField appPathField) {
        String appPath = appPathField.getText().trim();
        if (appPath.isEmpty()) {
            logger.error("请输入或选择应用程序路径");
            return;
        }

        File file = new File(appPath).getAbsoluteFile();
        if (!file.exists()) {
            logger.error("指定路径不存在: " + appPath);
            return;
        }
        if (!file.isFile()) {
            logger.error("指定路径不是文件: " + appPath);
            return;
        }

        String normalizedPath = file.toPath().normalize().toString();

        if (containsAppPath(normalizedPath)) {
            logger.info("应用程序已存在: " + normalizedPath);
            return;
        }

        String launcherName = file.getName().toLowerCase(Locale.ROOT);
        String defaultProcessName = launcherProcessMapSupplier.get()
                .getOrDefault(launcherName, launcherName);
        Optional<String> processName = askProcessName(defaultProcessName);
        if (processName.isEmpty()) {
            logger.info("用户取消了添加操作");
            return;
        }

        List<AppInfo> updatedAppInfos = snapshotAppInfos();
        updatedAppInfos.add(new AppInfo(normalizedPath, processName.get()));
        if (!commitAppInfos(updatedAppInfos)) {
            return;
        }
        logger.info("已添加应用程序: " + normalizedPath
                + " [检测进程名: " + processName.get() + "]");
        appPathField.clear();
    }

    /**
     * 移除选中的应用。
     *
     * @param selectedIndex 选中索引
     */
    public void remove(int selectedIndex) {
        List<AppInfo> updatedAppInfos = snapshotAppInfos();
        if (selectedIndex < 0 || selectedIndex >= updatedAppInfos.size()) {
            logger.error("请先选择要移除的应用程序");
            return;
        }

        AppInfo removed = updatedAppInfos.get(selectedIndex);
        if (!confirmRemove(removed)) {
            logger.info("用户取消了移除操作");
            return;
        }

        updatedAppInfos.remove(selectedIndex);
        if (!commitAppInfos(updatedAppInfos)) {
            return;
        }
        logger.info("已从列表移除: " + removed.getAppPath());
        killRemovedApp.accept(removed);
    }

    /**
     * 上移选中的应用。
     *
     * @param selectedIndex 选中索引
     * @param lastSelectedIndex 最后选中索引
     * @return 移动后的最后选中索引
     */
    public int moveUp(int selectedIndex, int lastSelectedIndex) {
        int effectiveIndex = resolveSelectedIndex(selectedIndex, lastSelectedIndex);
        if (effectiveIndex > 0) {
            List<AppInfo> updatedAppInfos = snapshotAppInfos();
            Collections.swap(updatedAppInfos, effectiveIndex, effectiveIndex - 1);
            if (!commitAppInfos(updatedAppInfos)) {
                return lastSelectedIndex;
            }

            int newSelectedIndex = effectiveIndex - 1;
            selectAndFocus.accept(newSelectedIndex);
            logger.info("已将应用程序上移: " + appInfos.get(newSelectedIndex).getAppPath());
            return newSelectedIndex;
        }

        if (effectiveIndex == 0) {
            logger.info("已经是第一个，无法上移");
        } else {
            logger.error("请先选择要移动的应用程序");
        }
        return lastSelectedIndex;
    }

    /**
     * 下移选中的应用。
     *
     * @param selectedIndex 选中索引
     * @param lastSelectedIndex 最后选中索引
     * @return 移动后的最后选中索引
     */
    public int moveDown(int selectedIndex, int lastSelectedIndex) {
        int effectiveIndex = resolveSelectedIndex(selectedIndex, lastSelectedIndex);
        if (effectiveIndex >= 0 && effectiveIndex < appInfos.size() - 1) {
            List<AppInfo> updatedAppInfos = snapshotAppInfos();
            Collections.swap(updatedAppInfos, effectiveIndex, effectiveIndex + 1);
            if (!commitAppInfos(updatedAppInfos)) {
                return lastSelectedIndex;
            }

            int newSelectedIndex = effectiveIndex + 1;
            selectAndFocus.accept(newSelectedIndex);
            logger.info("已将应用程序下移: " + appInfos.get(newSelectedIndex).getAppPath());
            return newSelectedIndex;
        }

        if (effectiveIndex >= 0 && effectiveIndex == appInfos.size() - 1) {
            logger.info("已经是最后一个，无法下移");
        } else {
            logger.error("请先选择要移动的应用程序");
        }
        return lastSelectedIndex;
    }

    /**
     * 清空应用列表。
     */
    public void clear() {
        if (!confirmClear()) {
            logger.info("用户取消了清空操作");
            return;
        }

        List<AppInfo> appsToKill = snapshotAppInfos();
        if (!commitAppInfos(List.of())) {
            return;
        }
        logger.info("已清除所有应用程序路径");
        killAllApps.accept(appsToKill);
    }

    private boolean containsAppPath(String appPath) {
        for (AppInfo appInfo : snapshotAppInfos()) {
            if (samePath(appInfo.getAppPath(), appPath)) {
                return true;
            }
        }
        return false;
    }

    private boolean commitAppInfos(List<AppInfo> updatedAppInfos) {
        List<AppInfo> snapshot = List.copyOf(updatedAppInfos);
        if (!saveAppInfos.test(snapshot)) {
            logger.error("应用配置保存失败，列表未做更改");
            return false;
        }
        synchronized (appInfos) {
            appInfos.clear();
            appInfos.addAll(snapshot);
        }
        updateAppList.run();
        return true;
    }

    private List<AppInfo> snapshotAppInfos() {
        synchronized (appInfos) {
            return new ArrayList<>(appInfos);
        }
    }

    private boolean samePath(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        try {
            Path firstPath = Path.of(first).toAbsolutePath().normalize();
            Path secondPath = Path.of(second).toAbsolutePath().normalize();
            return WindowsProcessSupport.isWindows()
                    ? firstPath.toString().equalsIgnoreCase(secondPath.toString())
                    : firstPath.equals(secondPath);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private Optional<String> askProcessName(String defaultProcessName) {
        TextInputDialog dialog = new TextInputDialog(defaultProcessName);
        FxTheme.apply(dialog);
        dialog.setTitle("设置检测进程名");
        dialog.setHeaderText("请输入检测进程名（通常为实际进程名）");
        dialog.setContentText("进程名：");

        Optional<String> result = dialog.showAndWait();
        return result.map(String::trim)
                .map(value -> value.isEmpty() ? defaultProcessName : value);
    }

    private boolean confirmRemove(AppInfo removed) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        FxTheme.apply(alert);
        alert.setTitle("确认移除");
        alert.setHeaderText("确认要移除所选应用程序吗？");
        alert.setContentText("[" + removed.getAppPath() + "] 会被移除，相关进程将被终止。是否继续？");
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private boolean confirmClear() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        FxTheme.apply(alert);
        alert.setTitle("确认清空");
        alert.setHeaderText("确认要清除所有应用程序路径吗？");
        alert.setContentText("此操作将终止所有已启动的进程并清空列表，是否继续？");
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private int resolveSelectedIndex(int selectedIndex, int lastSelectedIndex) {
        if (selectedIndex == -1 && lastSelectedIndex != -1) {
            selectAndFocus.accept(lastSelectedIndex);
            return lastSelectedIndex;
        }
        return selectedIndex;
    }
}
