package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.JarProjectRuntimeStatus;
import plugin.javafxtools.model.JarProjectStatusSummary;
import plugin.javafxtools.model.ProjectConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarProjectStatusModelTest {
    @Test
    void summaryCountsRunningConflictsAndErrors() {
        JarProjectStatusModel model = new JarProjectStatusModel();
        List<ProjectConfig> projects = List.of(project(1), project(2), project(3), project(4));
        long version = model.beginBatch(projects);
        model.applyBatch(version, Map.of(
                1, JarProjectRuntimeStatus.RUNNING,
                2, JarProjectRuntimeStatus.STOPPED,
                3, JarProjectRuntimeStatus.CONFLICT,
                4, JarProjectRuntimeStatus.ERROR), Set.of());

        JarProjectStatusSummary summary = model.summarize(projects.size());

        assertEquals("1 / 4 运行中 · 1 异常 · 1 端口冲突", summary.displayText());
        assertEquals(JarProjectStatusSummary.Tone.ERROR, summary.tone());
    }

    @Test
    void staleBatchResultIsRejected() {
        JarProjectStatusModel model = new JarProjectStatusModel();
        ProjectConfig project = project(1);
        long oldVersion = model.beginBatch(List.of(project));
        model.beginBatch(List.of(project));

        assertFalse(model.applyBatch(oldVersion,
                Map.of(1, JarProjectRuntimeStatus.RUNNING), Set.of()));
        assertEquals(JarProjectRuntimeStatus.CHECKING, model.statusOf(project));
    }

    @Test
    void activeOperationStatusIsProtectedFromBatchResult() {
        JarProjectStatusModel model = new JarProjectStatusModel();
        ProjectConfig project = project(1);
        long version = model.beginBatch(List.of(project));
        model.setStatus(project, JarProjectRuntimeStatus.STARTING);

        assertTrue(model.applyBatch(version,
                Map.of(1, JarProjectRuntimeStatus.STOPPED), Set.of(1)));
        assertEquals(JarProjectRuntimeStatus.STARTING, model.statusOf(project));
    }

    private ProjectConfig project(int id) {
        ProjectConfig project = new ProjectConfig();
        project.setId(id);
        project.setName("project-" + id);
        project.setDefaultPort(18080 + id);
        return project;
    }
}
