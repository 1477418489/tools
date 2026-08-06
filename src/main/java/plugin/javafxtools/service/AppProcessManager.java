package plugin.javafxtools.service;

import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.model.AppProcessStatus;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 启动项工具的外部进程管理器。
 *
 * @author wwj
 */
public class AppProcessManager {
    private static final int PROCESS_TERMINATE_TIMEOUT_MS = 1500;

    private final ModuleLogger logger;
    private final Map<String, AppProcessStatus> processStatusCache;
    private final Runnable scheduleUiUpdate;
    private final Map<String, ManagedProcess> managedProcesses = new ConcurrentHashMap<>();

    /**
     * 创建启动项进程管理器。
     *
     * @param logger 日志输出接口
     * @param processStatusCache 状态缓存
     * @param scheduleUiUpdate UI 刷新回调
     */
    public AppProcessManager(ModuleLogger logger,
                             Map<String, AppProcessStatus> processStatusCache,
                             Runnable scheduleUiUpdate) {
        this.logger = logger;
        this.processStatusCache = processStatusCache;
        this.scheduleUiUpdate = scheduleUiUpdate;
    }

    /**
     * 检查单个进程的运行状态。无法取得系统状态时保留异常语义。
     *
     * @param appPath 启动项路径
     * @param processName 进程名
     * @return 是否运行中
     * @throws IOException 无法读取系统进程状态
     */
    public boolean isProcessRunning(String appPath, String processName) throws IOException {
        if (processName == null || processName.trim().isEmpty()) {
            return false;
        }

        if (checkManagedProcess(appPath)) {
            return true;
        }
        if (hasManagedProcessWithName(processName)) {
            return false;
        }

        return WindowsProcessSupport.isProcessRunning(processName);
    }

    /**
     * 使用一份系统进程快照解析多个启动项的运行状态。
     *
     * @param appInfos 要检查的启动项
     * @return 以应用路径为键的运行状态
     * @throws IOException 无法获取系统进程快照
     */
    public Map<String, Boolean> captureRunningStates(List<AppInfo> appInfos) throws IOException {
        Map<String, Boolean> result = new LinkedHashMap<>();
        List<AppInfo> unresolvedApps = new ArrayList<>();

        for (AppInfo appInfo : appInfos) {
            String appPath = appInfo.getAppPath();
            String processName = AppLauncherStatusService.resolveCheckName(appInfo);
            if (checkManagedProcess(appPath)) {
                result.put(appPath, true);
            } else if (hasManagedProcessWithName(processName)) {
                result.put(appPath, false);
            } else {
                unresolvedApps.add(appInfo);
            }
        }

        if (!unresolvedApps.isEmpty()) {
            Map<String, List<Long>> systemSnapshot = WindowsProcessSupport.captureProcessSnapshot();
            for (AppInfo appInfo : unresolvedApps) {
                String processName = AppLauncherStatusService.resolveCheckName(appInfo);
                result.put(appInfo.getAppPath(), systemSnapshot.containsKey(
                        WindowsProcessSupport.normalizeImageName(processName)));
            }
        }

        return result;
    }

    /**
     * 清理已经不属于当前启动项列表的状态缓存，限制长期增删配置后的内存占用。
     *
     * @param appInfos 当前启动项快照
     */
    public void retainCachesFor(Collection<AppInfo> appInfos) {
        Set<String> paths = appInfos.stream()
                .map(AppInfo::getAppPath)
                .collect(Collectors.toUnmodifiableSet());
        processStatusCache.keySet().retainAll(paths);
    }

    /**
     * 终止进程。
     *
     * @param appPath 启动项路径
     * @param processName 进程名
     * @return 是否已终止
     */
    public boolean killProcess(String appPath, String processName) {
        if (processName == null || processName.trim().isEmpty()) {
            return false;
        }

        String key = pathKey(appPath);
        ManagedProcess managed = key == null ? null : managedProcesses.get(key);
        if (managed != null) {
            if (managed.process().isAlive()) {
                return terminateManagedProcess(key, appPath, managed);
            }
            managedProcesses.remove(key, managed);
        }
        if (hasManagedProcessWithName(processName)) {
            logger.info("未找到该启动项对应的托管进程，已跳过共享进程名终止: " + processName);
            return false;
        }

        try {
            List<Long> processIds = WindowsProcessSupport.findProcessIdsByImageName(processName);
            if (processIds.size() > 1) {
                logger.error("检测到 " + processIds.size() + " 个同名进程，已取消批量终止: "
                        + processName);
                return false;
            }
            if (processIds.isEmpty()) {
                return false;
            }
            boolean killed = WindowsProcessSupport.killProcessTreeByPid(processIds.getFirst());
            if (killed) {
                logger.debug("成功终止Windows进程: " + processName);
            }
            return killed;
        } catch (IOException e) {
            logger.error("系统命令终止进程失败: " + processName + " - " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 工具关闭时仅解除托管进程跟踪，不终止用户已启动的外部程序。
     */
    public void detachManagedProcessesOnly() {
        if (managedProcesses.isEmpty()) {
            logger.info("没有托管进程需要解除跟踪");
            return;
        }
        logger.info("解除 " + managedProcesses.size() + " 个托管进程跟踪，外部程序继续运行");
        managedProcesses.clear();
        logger.info("进程跟踪解除完成，独立进程继续运行");
    }

    /**
     * 启动外部进程。
     *
     * @param path 进程路径
     * @param processName 状态检查使用的进程名
     * @return 启动包装进程
     * @throws IOException 启动异常
     */
    public Process startProcess(String path, String processName) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("进程路径不能为空");
        }
        if (processName == null || processName.isBlank()) {
            throw new IllegalArgumentException("进程名不能为空");
        }

        File execFile = new File(path);
        if (!execFile.exists()) {
            throw new FileNotFoundException("可执行文件不存在: " + path);
        }

        ProcessBuilder builder = createProcessBuilder(path);
        builder.directory(execFile.getParentFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().remove("JAVA_TOOL_OPTIONS");

        Process process = builder.start();
        String key = pathKey(path);
        ManagedProcess managed = new ManagedProcess(processName, process);
        managedProcesses.put(key, managed);
        registerManagedProcessExit(key, path, managed);
        processStatusCache.put(path, new AppProcessStatus(true, 0));
        scheduleUiUpdate.run();
        logger.debug("成功启动独立进程: " + path + " (PID: " + process.pid() + ")");
        return process;
    }

    private boolean checkManagedProcess(String appPath) {
        String key = pathKey(appPath);
        ManagedProcess managed = key == null ? null : managedProcesses.get(key);
        if (managed == null) {
            return false;
        }
        if (managed.process().isAlive()) {
            return true;
        }
        managedProcesses.remove(key, managed);
        return false;
    }

    private boolean hasManagedProcessWithName(String processName) {
        return managedProcesses.values().stream()
                .anyMatch(managed -> managed.process().isAlive()
                        && managed.processName().equalsIgnoreCase(processName));
    }

    private boolean terminateManagedProcess(String key, String path, ManagedProcess managed) {
        Process process = managed.process();
        try {
            if (WindowsProcessSupport.isWindows()) {
                boolean killed = WindowsProcessSupport.killProcessTreeByPid(process.pid());
                if (!killed && process.isAlive()) {
                    process.destroyForcibly();
                }
                if (process.isAlive()) {
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                }
            } else {
                process.destroy();
                if (!process.waitFor(PROCESS_TERMINATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                }
            }
            if (process.isAlive()) {
                logger.error("终止托管进程超时: " + path);
                return false;
            }
            logger.debug("已终止托管进程: " + path);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        } catch (IOException e) {
            logger.error("终止托管进程失败: " + path + " - " + e.getMessage());
            return false;
        } finally {
            if (!process.isAlive()) {
                managedProcesses.remove(key, managed);
            }
        }
    }

    private void registerManagedProcessExit(String key, String path, ManagedProcess managed) {
        managed.process().onExit().thenRun(() -> {
            if (managedProcesses.remove(key, managed)) {
                processStatusCache.put(path, new AppProcessStatus(false, 0));
                logger.debug("托管进程已退出: " + path);
                scheduleUiUpdate.run();
            }
        });
    }

    private ProcessBuilder createProcessBuilder(String path) {
        if (WindowsProcessSupport.isWindows()) {
            return WindowsProcessSupport.createVisibleProcessBuilder(path);
        }

        return new ProcessBuilder(path);
    }

    private String pathKey(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            String normalized = Path.of(path).toAbsolutePath().normalize().toString();
            return WindowsProcessSupport.isWindows()
                    ? normalized.toLowerCase(Locale.ROOT)
                    : normalized;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private record ManagedProcess(String processName, Process process) {
    }
}
