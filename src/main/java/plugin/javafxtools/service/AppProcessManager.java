package plugin.javafxtools.service;

import javafx.application.Platform;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppProcessStatus;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 启动项工具的外部进程管理器。
 *
 * @author wwj
 */
public class AppProcessManager {
    private static final int PROCESS_TERMINATE_TIMEOUT_MS = 1500;
    private static final long PROCESS_CHECK_CACHE_TTL = 2000;

    private final ModuleLogger logger;
    private final ExecutorService backgroundExecutor;
    private final Map<String, AppProcessStatus> processStatusCache;
    private final Runnable scheduleUiUpdate;
    private final Map<String, Process> managedProcesses = new ConcurrentHashMap<>();
    private final Map<String, Long> processCheckCache = new ConcurrentHashMap<>();

    /**
     * 创建启动项进程管理器。
     *
     * @param logger 日志输出接口
     * @param backgroundExecutor 后台执行器
     * @param processStatusCache 状态缓存
     * @param scheduleUiUpdate UI 刷新回调
     */
    public AppProcessManager(ModuleLogger logger,
                             ExecutorService backgroundExecutor,
                             Map<String, AppProcessStatus> processStatusCache,
                             Runnable scheduleUiUpdate) {
        this.logger = logger;
        this.backgroundExecutor = backgroundExecutor;
        this.processStatusCache = processStatusCache;
        this.scheduleUiUpdate = scheduleUiUpdate;
    }

    public boolean isProcessRunning(String processName) {
        return isProcessRunning(processName, false);
    }

    /**
     * 进程运行状态检查，支持强制绕过缓存。
     *
     * @param processName 进程名
     * @param forceCheck 是否强制检查
     * @return 是否运行中
     */
    public boolean isProcessRunning(String processName, boolean forceCheck) {
        if (processName == null || processName.trim().isEmpty()) {
            return false;
        }

        if (checkManagedProcesses(processName)) {
            return true;
        }

        if (!forceCheck) {
            String cacheKey = processName.toLowerCase();
            Long lastCheck = processCheckCache.get(cacheKey);
            if (lastCheck != null && (System.currentTimeMillis() - lastCheck) < PROCESS_CHECK_CACHE_TTL) {
                return false;
            }
        }

        try {
            boolean running = WindowsProcessSupport.isProcessRunning(processName);
            String cacheKey = processName.toLowerCase();
            if (running) {
                processCheckCache.remove(cacheKey);
            } else {
                processCheckCache.put(cacheKey, System.currentTimeMillis());
            }
            return running;
        } catch (IOException e) {
            logger.debug("进程检查错误: " + processName + " - " + e.getMessage());
            return false;
        }
    }

    public void clearProcessCache(String processName) {
        if (processName != null) {
            processCheckCache.remove(processName.toLowerCase());
            logger.debug("已清理进程缓存: " + processName);
        }
    }

    public void clearAllProcessCache() {
        processCheckCache.clear();
        logger.debug("已清理所有进程缓存");
    }

    /**
     * 终止进程。
     *
     * @param processName 进程名
     * @return 是否已终止
     */
    public boolean killProcess(String processName) {
        if (processName == null || processName.trim().isEmpty()) {
            return false;
        }

        boolean killed = false;
        for (Map.Entry<String, Process> entry : managedProcesses.entrySet()) {
            String path = entry.getKey();
            Process managed = entry.getValue();
            if ((path.endsWith(processName) || new File(path).getName().equalsIgnoreCase(processName))
                    && managed != null && managed.isAlive()) {
                killed |= terminateManagedProcess(path, managed);
            }
        }

        try {
            killed |= WindowsProcessSupport.killProcessByImageName(processName);
            processCheckCache.remove(processName.toLowerCase());
            if (killed) {
                logger.debug("成功终止Windows进程: " + processName);
            }
        } catch (IOException e) {
            logger.error("系统命令终止进程失败: " + processName + " - " + e.getMessage());
        }

        return killed;
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
        processCheckCache.clear();
        logger.info("进程跟踪解除完成，独立进程继续运行");
    }

    /**
     * 启动外部进程。
     *
     * @param path 进程路径
     * @return 启动包装进程
     * @throws IOException 启动异常
     */
    public Process startProcess(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("进程路径不能为空");
        }

        File execFile = new File(path);
        if (!execFile.exists()) {
            throw new FileNotFoundException("可执行文件不存在: " + path);
        }

        ProcessBuilder builder = createProcessBuilder(path);
        builder.directory(execFile.getParentFile());
        builder.redirectErrorStream(false);
        builder.environment().remove("JAVA_TOOL_OPTIONS");

        Process process = builder.start();
        managedProcesses.put(path, process);
        registerManagedProcessExit(path, process);
        monitorProcessIndependently(path, process);
        logger.debug("成功启动独立进程: " + path + " (PID: " + process.pid() + ")");
        return process;
    }

    private boolean checkManagedProcesses(String processName) {
        return managedProcesses.entrySet().parallelStream()
                .anyMatch(entry -> {
                    String path = entry.getKey();
                    Process managed = entry.getValue();
                    return (path.endsWith(processName) ||
                            new File(path).getName().equalsIgnoreCase(processName)) &&
                            managed.isAlive();
                });
    }

    private boolean terminateManagedProcess(String path, Process process) {
        try {
            if (WindowsProcessSupport.isWindows()) {
                boolean killed = WindowsProcessSupport.killProcessTreeByPid(process.pid());
                if (!killed && process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                }
            } else {
                process.destroy();
                if (!process.waitFor(PROCESS_TERMINATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                }
            }
            logger.debug("已终止托管进程: " + path);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return true;
        } catch (IOException e) {
            logger.error("终止托管进程失败: " + path + " - " + e.getMessage());
            return false;
        } finally {
            managedProcesses.remove(path, process);
        }
    }

    private void monitorProcessIndependently(String path, Process process) {
        backgroundExecutor.submit(() -> {
            try {
                logger.info(String.format("独立进程已启动: %s (PID: %d)", path, process.pid()));
                String procName = new File(path).getName();
                boolean actuallyRunning = false;
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(2000);
                    actuallyRunning = isProcessRunning(procName, true);
                    if (actuallyRunning) {
                        break;
                    }
                    logger.debug(String.format("进程检查第%d次: %s 未检测到", i + 1, procName));
                }
                if (actuallyRunning) {
                    logger.info(String.format("进程启动确认成功: %s", path));
                } else {
                    logger.info(String.format("进程可能未成功启动: %s", path));
                }
                Platform.runLater(scheduleUiUpdate);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("进程监控被中断: " + path);
            } catch (RuntimeException e) {
                logger.error("进程监控出错: " + path + " - " + e.getMessage());
            }
        });
    }

    private void registerManagedProcessExit(String path, Process process) {
        process.onExit().thenRun(() -> {
            if (managedProcesses.remove(path, process)) {
                processStatusCache.put(path, new AppProcessStatus(false, 0));
                processCheckCache.remove(new File(path).getName().toLowerCase());
                logger.debug("托管进程已退出: " + path);
                Platform.runLater(scheduleUiUpdate);
            }
        });
    }

    private ProcessBuilder createProcessBuilder(String path) {
        if (WindowsProcessSupport.isWindows()) {
            return WindowsProcessSupport.createVisibleProcessBuilder(path);
        }

        return new ProcessBuilder(path);
    }
}
