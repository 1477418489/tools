package plugin.javafxtools.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import plugin.javafxtools.model.MemoReminder;
import plugin.javafxtools.model.ReminderScheduleMode;
import plugin.javafxtools.util.AppDataPaths;
import plugin.javafxtools.util.AtomicFileWriter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 备忘提醒配置的本地 JSON 存储。
 *
 * @author wwj
 */
public class MemoReminderStore {
    private static final Path DEFAULT_DATA_FILE = AppDataPaths.dataFile("memo_reminders.json");
    private static final Type REMINDER_LIST_TYPE = new TypeToken<List<MemoReminder>>() {
    }.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path dataFile;

    public MemoReminderStore() {
        this(DEFAULT_DATA_FILE);
    }

    MemoReminderStore(Path dataFile) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile");
    }

    /**
     * 从本地文件加载备忘提醒。
     *
     * @return 备忘提醒列表
     * @throws IOException 文件读取失败
     */
    public List<MemoReminder> load() throws IOException {
        if (!Files.exists(dataFile)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(readStoredReminders());
    }

    /**
     * 保存备忘提醒到本地文件。
     *
     * @param reminders 备忘提醒列表
     * @throws IOException 文件写入失败
     */
    public void save(List<MemoReminder> reminders) throws IOException {
        validateCurrentSchema(reminders);
        validateExistingFileBeforeOverwrite();
        String json = gson.toJson(reminders);
        AtomicFileWriter.writeUtf8(dataFile, json);
    }

    private List<MemoReminder> readStoredReminders() throws IOException {
        String json = Files.readString(dataFile, StandardCharsets.UTF_8);
        List<MemoReminder> loaded = gson.fromJson(json, REMINDER_LIST_TYPE);
        validateCurrentSchema(loaded);
        return loaded;
    }

    private void validateExistingFileBeforeOverwrite() throws IOException {
        if (!Files.exists(dataFile)) {
            return;
        }
        try {
            readStoredReminders();
        } catch (Exception e) {
            throw new IOException("现有备忘提醒配置无效，拒绝覆盖", e);
        }
    }

    private void validateCurrentSchema(List<MemoReminder> reminders) throws IOException {
        if (reminders == null) {
            throw new IOException("配置根节点必须是数组");
        }

        Set<Long> reminderIds = new HashSet<>();
        for (MemoReminder reminder : reminders) {
            if (reminder == null
                    || reminder.getId() <= 0
                    || !reminderIds.add(reminder.getId())
                    || reminder.getContent() == null
                    || reminder.getContent().isBlank()
                    || reminder.getScheduleMode() == null
                    || reminder.getNextTriggerEpochMillis() <= 0) {
                throw new IOException("备忘提醒配置不符合当前格式");
            }

            if (reminder.getScheduleMode() == ReminderScheduleMode.AT_TIME) {
                validateAtTimeReminder(reminder);
            } else {
                validateIntervalReminder(reminder);
            }
        }
    }

    private void validateIntervalReminder(MemoReminder reminder) throws IOException {
        if (reminder.getInterval() <= 0 || reminder.getUnit() == null) {
            throw new IOException("周期提醒配置不符合当前格式");
        }

        int totalTimes = reminder.getTotalTimes();
        int remainingTimes = reminder.getRemainingTimes();
        boolean invalidUnlimited = totalTimes <= 0 && remainingTimes != -1;
        boolean invalidFinite = totalTimes > 0
                && (remainingTimes < 0 || remainingTimes > totalTimes);
        if (invalidUnlimited || invalidFinite || reminder.isActive() && remainingTimes == 0) {
            throw new IOException("周期提醒次数状态不符合当前格式");
        }
    }

    private void validateAtTimeReminder(MemoReminder reminder) throws IOException {
        int remainingTimes = reminder.getRemainingTimes();
        boolean invalidShape = reminder.getInterval() != 0
                || reminder.getUnit() != null
                || reminder.getTotalTimes() != 1
                || remainingTimes < 0
                || remainingTimes > 1;
        if (invalidShape || reminder.isActive() && remainingTimes == 0) {
            throw new IOException("指定时间提醒配置不符合当前格式");
        }
    }
}
