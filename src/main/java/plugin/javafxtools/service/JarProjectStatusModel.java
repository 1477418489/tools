package plugin.javafxtools.service;

import plugin.javafxtools.model.JarProjectRuntimeStatus;
import plugin.javafxtools.model.JarProjectStatusSummary;
import plugin.javafxtools.model.ProjectConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JAR 项目状态缓存和异步批次版本控制。
 */
public final class JarProjectStatusModel {
    private final Map<Integer, JarProjectRuntimeStatus> statuses = new HashMap<>();
    private final AtomicLong batchVersion = new AtomicLong();

    public long beginBatch(List<ProjectConfig> projects) {
        long version = batchVersion.incrementAndGet();
        Set<Integer> projectIds = projects.stream()
                .map(ProjectConfig::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        statuses.keySet().removeIf(projectId -> !projectIds.contains(projectId));
        projects.forEach(project -> statuses.put(
                project.getId(), JarProjectRuntimeStatus.CHECKING));
        return version;
    }

    public boolean isBatchCurrent(long version) {
        return batchVersion.get() == version;
    }

    public boolean applyBatch(long version,
                              Map<Integer, JarProjectRuntimeStatus> checkedStatuses,
                              Set<Integer> protectedProjectIds) {
        if (!isBatchCurrent(version)) {
            return false;
        }
        checkedStatuses.forEach((projectId, status) -> {
            if (!protectedProjectIds.contains(projectId)) {
                statuses.put(projectId, status);
            }
        });
        return true;
    }

    public void failBatch(long version, List<ProjectConfig> projects) {
        if (isBatchCurrent(version)) {
            projects.forEach(project -> statuses.put(
                    project.getId(), JarProjectRuntimeStatus.ERROR));
        }
    }

    public void invalidateBatch() {
        batchVersion.incrementAndGet();
    }

    public void setStatus(ProjectConfig project, JarProjectRuntimeStatus status) {
        if (project != null && project.getId() > 0) {
            statuses.put(project.getId(), status);
        }
    }

    public JarProjectRuntimeStatus statusOf(ProjectConfig project) {
        if (project == null || project.getId() <= 0) {
            return JarProjectRuntimeStatus.UNKNOWN;
        }
        return statuses.getOrDefault(project.getId(), JarProjectRuntimeStatus.UNKNOWN);
    }

    public JarProjectStatusSummary summarize(int total) {
        long running = count(JarProjectRuntimeStatus.RUNNING);
        long checking = statuses.values().stream().filter(JarProjectRuntimeStatus::isBusy).count();
        long errors = count(JarProjectRuntimeStatus.ERROR);
        long conflicts = count(JarProjectRuntimeStatus.CONFLICT);
        return new JarProjectStatusSummary(total, running, checking, errors, conflicts);
    }

    private long count(JarProjectRuntimeStatus expected) {
        return statuses.values().stream().filter(expected::equals).count();
    }
}
