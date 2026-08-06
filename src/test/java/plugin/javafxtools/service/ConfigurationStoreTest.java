package plugin.javafxtools.service;

import javafx.scene.control.TextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.base.ModuleLogger;
import plugin.javafxtools.model.AppInfo;
import plugin.javafxtools.model.HttpTemplate;
import plugin.javafxtools.model.IntervalUnit;
import plugin.javafxtools.model.KeepAliveConfig;
import plugin.javafxtools.model.KeepAliveMethod;
import plugin.javafxtools.model.MemoReminder;
import plugin.javafxtools.model.ProjectConfig;
import plugin.javafxtools.model.ReminderScheduleMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void appLauncherStoreRoundTripsCurrentSchemaOnly() throws Exception {
        Path appFile = tempDirectory.resolve("app_launcher_paths.json");
        AppLauncherStore store = new AppLauncherStore(new NoOpLogger(), appFile);

        assertEquals("erl.exe", store.loadProcessMap().get("rabbitmq-server.bat"));
        assertEquals("RedisDesktopManager.exe",
                store.loadProcessMap().get("redisdesktopmanager.exe"));

        store.saveAppInfos(List.of(new AppInfo("C:\\工具\\应用.exe", "应用.exe")));

        List<AppInfo> loaded = store.loadAppInfos();
        assertEquals(1, loaded.size());
        assertEquals("C:\\工具\\应用.exe", loaded.getFirst().getAppPath());
        assertEquals("应用.exe", loaded.getFirst().getProcessName());
        assertFalse(store.saveAppInfos(null));
        assertEquals(loaded.getFirst().getAppPath(), store.loadAppInfos().getFirst().getAppPath());

        Files.writeString(appFile, "[\"C:\\\\旧格式.exe\"]", StandardCharsets.UTF_8);
        assertTrue(store.loadAppInfos().isEmpty());
    }

    @Test
    void jarProjectStoreStartsEmptyAndRoundTripsProjects() {
        Path configFile = tempDirectory.resolve("jar_launcher_projects.json");
        JarProjectStore store = new JarProjectStore(_ -> { }, configFile);

        assertTrue(store.loadProjects().isEmpty());

        ProjectConfig project = new ProjectConfig();
        project.setId(7);
        project.setName("示例项目");
        project.setSourceJar(tempDirectory.resolve("build/app.jar").toString());
        project.setTargetJar(tempDirectory.resolve("deploy/app.jar").toString());
        project.setDefaultPort(8080);
        store.saveProjects(List.of(project));

        Map<Integer, ProjectConfig> loaded = store.loadProjects();
        assertEquals(List.of(7), List.copyOf(loaded.keySet()));
        assertEquals("示例项目", loaded.get(7).getName());
        assertEquals(project.getSourceJar(), loaded.get(7).getSourceJar());
        assertEquals(project.getTargetJar(), loaded.get(7).getTargetJar());
        assertFalse(store.saveProjects(null));
        assertEquals(List.of(7), List.copyOf(store.loadProjects().keySet()));

        project.setSourceLib(tempDirectory.resolve("build/lib").toString());
        project.setLibTarget(tempDirectory.resolve("deploy/lib").toString());
        project.setTargetJar(tempDirectory.resolve("build/lib/app.jar").toString());
        assertFalse(store.saveProjects(List.of(project)));
        assertEquals(List.of(7), List.copyOf(store.loadProjects().keySet()));
    }

    @Test
    void appLauncherStoreRejectsMalformedCurrentSchema() throws Exception {
        Path appFile = tempDirectory.resolve("app_launcher_paths.json");
        String invalidConfig = """
                [{"appPath":"relative.exe","processName":"relative.exe"}]
                """;
        Files.writeString(appFile, invalidConfig, StandardCharsets.UTF_8);

        AppLauncherStore store = new AppLauncherStore(new NoOpLogger(), appFile);

        assertTrue(store.loadAppInfos().isEmpty());
        assertFalse(store.saveAppInfos(List.of(
                new AppInfo(tempDirectory.resolve("app.exe").toString(), "app.exe"))));
        assertEquals(invalidConfig, Files.readString(appFile, StandardCharsets.UTF_8));
    }

    @Test
    void jarProjectStoreRejectsMalformedCurrentSchema() throws Exception {
        Path configFile = tempDirectory.resolve("jar_launcher_projects.json");
        String invalidConfig = """
                [{
                  "id": 7,
                  "name": "无效项目",
                  "sourceJar": "C:\\\\build\\\\app.jar",
                  "targetJar": "C:\\\\deploy\\\\app.jar",
                  "defaultPort": 70000
                }]
                """;
        Files.writeString(configFile, invalidConfig, StandardCharsets.UTF_8);

        JarProjectStore store = new JarProjectStore(_ -> { }, configFile);

        assertTrue(store.loadProjects().isEmpty());
        ProjectConfig validProject = new ProjectConfig();
        validProject.setId(1);
        validProject.setName("有效项目");
        validProject.setSourceJar(tempDirectory.resolve("source.jar").toString());
        validProject.setTargetJar(tempDirectory.resolve("target.jar").toString());
        validProject.setDefaultPort(8080);
        assertFalse(store.saveProjects(List.of(validProject)));
        assertEquals(invalidConfig, Files.readString(configFile, StandardCharsets.UTF_8));
    }

    @Test
    void memoReminderStoreRoundTripsCurrentScheduleModes() throws Exception {
        MemoReminderStore store = new MemoReminderStore(tempDirectory.resolve("memo_reminders.json"));
        MemoReminder reminder = new MemoReminder(1L, "检查发布结果", 5, IntervalUnit.MINUTES, 2);
        reminder.setNextTriggerEpochMillis(123456789L);
        MemoReminder alarm = MemoReminder.atTime(2L, "参加会议", 223456789L);

        store.save(List.of(reminder, alarm));

        List<MemoReminder> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals("检查发布结果", loaded.getFirst().getContent());
        assertEquals(ReminderScheduleMode.INTERVAL, loaded.getFirst().getScheduleMode());
        assertEquals(123456789L, loaded.getFirst().getNextTriggerEpochMillis());
        assertEquals(ReminderScheduleMode.AT_TIME, loaded.get(1).getScheduleMode());
        assertEquals("指定时间", loaded.get(1).getDisplaySchedule());
        assertThrows(java.io.IOException.class, () -> store.save(null));
        assertEquals(2, store.load().size());
    }

    @Test
    void memoReminderStoreRejectsMalformedCurrentSchema() throws Exception {
        Path dataFile = tempDirectory.resolve("memo_reminders.json");
        String invalidConfig = """
                [{
                  "id": 1,
                  "content": "无效提醒",
                  "scheduleMode": "INTERVAL",
                  "interval": 5,
                  "unit": "MINUTES",
                  "totalTimes": 2,
                  "remainingTimes": 0,
                  "nextTriggerEpochMillis": 123456789,
                  "active": true
                }]
                """;
        Files.writeString(dataFile, invalidConfig, StandardCharsets.UTF_8);

        MemoReminderStore store = new MemoReminderStore(dataFile);

        assertThrows(java.io.IOException.class, store::load);
        MemoReminder validReminder = new MemoReminder(2L, "有效提醒", 5, IntervalUnit.MINUTES, 1);
        validReminder.setNextTriggerEpochMillis(123456789L);
        assertThrows(java.io.IOException.class, () -> store.save(List.of(validReminder)));
        assertEquals(invalidConfig, Files.readString(dataFile, StandardCharsets.UTF_8));
    }

    @Test
    void memoReminderStoreRejectsSchemaWithoutScheduleMode() throws Exception {
        Path dataFile = tempDirectory.resolve("memo_reminders.json");
        String oldSchema = """
                [{
                  "id": 1,
                  "content": "旧格式提醒",
                  "interval": 5,
                  "unit": "MINUTES",
                  "totalTimes": 1,
                  "remainingTimes": 1,
                  "nextTriggerEpochMillis": 123456789,
                  "active": true
                }]
                """;
        Files.writeString(dataFile, oldSchema, StandardCharsets.UTF_8);

        MemoReminderStore store = new MemoReminderStore(dataFile);

        assertThrows(java.io.IOException.class, store::load);
        MemoReminder validReminder = MemoReminder.atTime(2L, "当前格式提醒", 223456789L);
        assertThrows(java.io.IOException.class, () -> store.save(List.of(validReminder)));
        assertEquals(oldSchema, Files.readString(dataFile, StandardCharsets.UTF_8));
    }

    @Test
    void keepAliveStoreRoundTripsConfig() throws Exception {
        KeepAliveConfigStore store = new KeepAliveConfigStore(tempDirectory.resolve("keepAlive.json"));
        KeepAliveConfig config = new KeepAliveConfig(
                "https://example.com", true, KeepAliveMethod.HTTP, 2, 4, IntervalUnit.MINUTES);

        store.saveConfigs(List.of(config));

        List<KeepAliveConfig> loaded = store.loadConfigs();
        assertEquals(1, loaded.size());
        assertEquals("https://example.com", loaded.getFirst().getDomain());
        assertEquals(KeepAliveMethod.HTTP, loaded.getFirst().getMethod());
        assertEquals(4, loaded.getFirst().getMaxInterval());
        assertThrows(java.io.IOException.class, () -> store.saveConfigs(null));
        assertEquals(1, store.loadConfigs().size());
    }

    @Test
    void keepAliveStoreRejectsNonBaselineSchemaWithoutRewritingIt() throws Exception {
        Path configFile = tempDirectory.resolve("keepAlive.json");
        String invalidConfig = """
                [{
                  "domain": "https://example.com",
                  "enabled": true,
                  "minInterval": 2,
                  "maxInterval": 4,
                  "unit": "MINUTES"
                }]
                """;
        Files.writeString(configFile, invalidConfig, StandardCharsets.UTF_8);
        KeepAliveConfigStore store = new KeepAliveConfigStore(configFile);

        assertThrows(java.io.IOException.class, store::loadConfigs);
        KeepAliveConfig validConfig = new KeepAliveConfig(
                "https://example.org", true, KeepAliveMethod.HTTP, 1, 2, IntervalUnit.MINUTES);
        assertThrows(java.io.IOException.class, () -> store.saveConfigs(List.of(validConfig)));
        assertEquals(invalidConfig, Files.readString(configFile, StandardCharsets.UTF_8));
        try (var files = Files.list(tempDirectory)) {
            assertEquals(List.of(configFile), files.toList());
        }
    }

    @Test
    void httpTemplateStoreRoundTripsTemplate() {
        HttpTemplateStore store = new HttpTemplateStore(tempDirectory.resolve("http_templates.json"));
        HttpTemplate template = new HttpTemplate(
                "https://example.com/api", "POST", "{}", "X-Test: 中文", "10", "5000", "10000");

        store.saveTemplates(Map.of("发布检查", template));

        Map<String, HttpTemplate> loaded = store.loadTemplates();
        assertEquals("POST", loaded.get("发布检查").method);
        assertEquals("X-Test: 中文", loaded.get("发布检查").headers);
        assertThrows(IllegalStateException.class, () -> store.saveTemplates(null));
        assertEquals("POST", store.loadTemplates().get("发布检查").method);
    }

    @Test
    void httpTemplateStoreRejectsMalformedCurrentSchema() throws Exception {
        Path templateFile = tempDirectory.resolve("http_templates.json");
        String invalidConfig = """
                {"无效模板": {
                  "url": "https://example.com/api",
                  "method": "post",
                  "params": "{}",
                  "headers": "",
                  "interval": "10",
                  "connectTimeout": "5000",
                  "readTimeout": "10000"
                }}
                """;
        Files.writeString(templateFile, invalidConfig, StandardCharsets.UTF_8);

        HttpTemplateStore store = new HttpTemplateStore(templateFile);

        assertThrows(IllegalStateException.class, store::loadTemplates);
        HttpTemplate validTemplate = new HttpTemplate(
                "https://example.org", "GET", "", "", "10", "5000", "10000");
        assertThrows(IllegalStateException.class,
                () -> store.saveTemplates(Map.of("有效模板", validTemplate)));
        assertEquals(invalidConfig, Files.readString(templateFile, StandardCharsets.UTF_8));
    }

    private static final class NoOpLogger implements ModuleLogger {
        @Override
        public void log(String level, String message) {
        }

        @Override
        public TextArea getLogArea() {
            return null;
        }
    }
}
