package plugin.javafxtools.service;

import javafx.scene.control.ButtonBar;
import org.junit.jupiter.api.Test;
import plugin.javafxtools.model.IntervalUnit;
import plugin.javafxtools.model.MemoReminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoReminderSchedulerServiceTest {
    @Test
    void onlyConfirmedCompletedReminderConsumesAnOccurrence() {
        assertTrue(MemoReminderSchedulerService.shouldCompleteReminder(
                ButtonBar.ButtonData.OK_DONE, true));
        assertFalse(MemoReminderSchedulerService.shouldCompleteReminder(
                ButtonBar.ButtonData.OK_DONE, false));
        assertFalse(MemoReminderSchedulerService.shouldCompleteReminder(
                ButtonBar.ButtonData.CANCEL_CLOSE, true));
        assertFalse(MemoReminderSchedulerService.shouldCompleteReminder(
                ButtonBar.ButtonData.OTHER, true));
    }

    @Test
    void intervalReminderAdvancesToNextCycleAfterCompletion() {
        MemoReminder reminder = new MemoReminder(1L, "周期任务", 5, IntervalUnit.MINUTES, 2);

        assertFalse(MemoReminderSchedulerService.advanceAfterCompletion(reminder, 1_000L));
        assertEquals(1, reminder.getRemainingTimes());
        assertEquals(301_000L, reminder.getNextTriggerEpochMillis());
        assertTrue(reminder.isActive());
    }

    @Test
    void atTimeReminderCompletesAfterItsSingleOccurrence() {
        MemoReminder reminder = MemoReminder.atTime(2L, "指定时间任务", 10_000L);

        assertTrue(MemoReminderSchedulerService.advanceAfterCompletion(reminder, 10_000L));
        assertEquals(0, reminder.getRemainingTimes());
        assertFalse(reminder.isActive());
    }
}
