package plugin.javafxtools.service;

import plugin.javafxtools.model.ProjectConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JAR 启动器按项目维护运行时状态。
 *
 * @author wwj
 */
public class JarLauncherRuntimeState {
    private static final int NO_RUNNING_PORT = -1;

    private final Map<Integer, Integer> runningPortsByProjectId = new ConcurrentHashMap<>();

    /**
     * 记录项目当前运行端口。
     *
     * @param project 项目配置
     * @param port 运行端口
     */
    public void recordRunningPort(ProjectConfig project, int port) {
        if (project == null || project.getId() <= 0 || port <= 0) {
            return;
        }
        runningPortsByProjectId.put(project.getId(), port);
    }

    /**
     * 清除项目运行端口。
     *
     * @param project 项目配置
     */
    public void clearRunningPort(ProjectConfig project) {
        if (project == null || project.getId() <= 0) {
            return;
        }
        runningPortsByProjectId.remove(project.getId());
    }

    /**
     * 获取项目当前运行端口，未记录时返回默认端口。
     *
     * @param project 项目配置
     * @return 运行端口或默认端口
     */
    public int resolveStatusPort(ProjectConfig project) {
        if (project == null) {
            return NO_RUNNING_PORT;
        }
        int runningPort = getRunningPort(project);
        return runningPort > 0 ? runningPort : project.getDefaultPort();
    }

    /**
     * 解析停止操作应使用的端口。
     *
     * @param project 项目配置
     * @param portText 端口输入框文本
     * @return 停止端口
     */
    public int resolveStopPort(ProjectConfig project, String portText) {
        if (project == null) {
            return NO_RUNNING_PORT;
        }

        int runningPort = getRunningPort(project);
        if (runningPort > 0) {
            return runningPort;
        }

        String trimmedPort = portText == null ? "" : portText.trim();
        if (!trimmedPort.isEmpty()) {
            try {
                return Integer.parseInt(trimmedPort);
            } catch (NumberFormatException e) {
                return project.getDefaultPort();
            }
        }
        return project.getDefaultPort();
    }

    private int getRunningPort(ProjectConfig project) {
        if (project == null || project.getId() <= 0) {
            return NO_RUNNING_PORT;
        }
        return runningPortsByProjectId.getOrDefault(project.getId(), NO_RUNNING_PORT);
    }
}
