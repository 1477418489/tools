package plugin.javafxtools.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LogMonitorAutomationTest {
    @Test
    void rejectsOversizedValuesBeforeBuildingAnInputScript() {
        assertThrows(IllegalArgumentException.class, () -> new LogMonitorAutomation(
                true, "429", "x".repeat(32_801), true, "continue", true,
                1, 1, 1, false, "", "", LogRemoteMatchAction.CONTINUE_INPUT));
        assertThrows(IllegalArgumentException.class, () -> new LogMonitorAutomation(
                true, "429", "Codex", true, "x".repeat(4_097), true,
                1, 1, 1, false, "", "", LogRemoteMatchAction.CONTINUE_INPUT));
    }

    @Test
    void rejectsCountsTheEditorCannotRepresent() {
        assertThrows(IllegalArgumentException.class, () -> new LogMonitorAutomation(
                true, "429", "Codex", true, "continue", true,
                1_000_001, 1, 1, false, "", "",
                LogRemoteMatchAction.CONTINUE_INPUT));
    }

    @Test
    void validatesEverySelectedTriggerRule() {
        LogMonitorAutomation automation = new LogMonitorAutomation(
                true, List.of("429", "503"), "Codex", true, "continue", true,
                1, 1, 1, false, "", "", LogRemoteMatchAction.CONTINUE_INPUT);

        automation.validate(List.of(
                new LogMonitorRule("429", "HTTP 429", "429",
                        LogMatchMode.CONTAINS, true, true),
                new LogMonitorRule("503", "HTTP 503", "503",
                        LogMatchMode.CONTAINS, true, true)));

        assertThrows(IllegalArgumentException.class, () -> automation.validate(List.of(
                new LogMonitorRule("429", "HTTP 429", "429",
                        LogMatchMode.CONTAINS, true, true))));
    }
}
