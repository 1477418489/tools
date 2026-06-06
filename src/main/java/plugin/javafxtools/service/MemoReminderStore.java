package plugin.javafxtools.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import plugin.javafxtools.model.MemoReminder;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 备忘提醒配置的本地 JSON 存储。
 *
 * @author wwj
 */
public class MemoReminderStore {
    private static final Path DATA_FILE = Path.of("userData", "memo_reminders.json");
    private static final Type REMINDER_LIST_TYPE = new TypeToken<List<MemoReminder>>() {
    }.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 从本地文件加载备忘提醒。
     *
     * @return 备忘提醒列表
     * @throws IOException 文件读取失败
     */
    public List<MemoReminder> load() throws IOException {
        if (!Files.exists(DATA_FILE)) {
            return new ArrayList<>();
        }
        String json = Files.readString(DATA_FILE, StandardCharsets.UTF_8);
        List<MemoReminder> loaded = gson.fromJson(json, REMINDER_LIST_TYPE);
        return loaded == null ? new ArrayList<>() : loaded;
    }

    /**
     * 保存备忘提醒到本地文件。
     *
     * @param reminders 备忘提醒列表
     * @throws IOException 文件写入失败
     */
    public void save(List<MemoReminder> reminders) throws IOException {
        Files.createDirectories(DATA_FILE.getParent());
        String json = gson.toJson(reminders == null ? new ArrayList<>() : reminders);
        Files.writeString(DATA_FILE, json, StandardCharsets.UTF_8);
    }
}
