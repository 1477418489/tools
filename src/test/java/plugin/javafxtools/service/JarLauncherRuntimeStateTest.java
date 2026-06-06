package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.ProjectConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JarLauncherRuntimeStateTest {
    @Test
    void resolveStopPortIgnoresOtherProjectsRecordedRunningPort() {
        JarLauncherRuntimeState runtimeState = new JarLauncherRuntimeState();
        ProjectConfig appOne = project(1, 18081);
        ProjectConfig appTwo = project(2, 18082);

        runtimeState.recordRunningPort(appOne, 18081);

        assertEquals(18082, runtimeState.resolveStopPort(appTwo, ""));
    }

    @Test
    void resolveStopPortUsesSelectedProjectsRecordedRunningPort() {
        JarLauncherRuntimeState runtimeState = new JarLauncherRuntimeState();
        ProjectConfig appOne = project(1, 18081);
        ProjectConfig appTwo = project(2, 18082);

        runtimeState.recordRunningPort(appOne, 18081);
        runtimeState.recordRunningPort(appTwo, 19082);

        assertEquals(19082, runtimeState.resolveStopPort(appTwo, ""));
    }

    private static ProjectConfig project(int id, int defaultPort) {
        ProjectConfig project = new ProjectConfig();
        project.setId(id);
        project.setName("app-" + id);
        project.setDefaultPort(defaultPort);
        return project;
    }
}
