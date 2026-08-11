package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.service.AppLauncherSettingsStore.Settings;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppLauncherSettingsStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void defaultsAndRoundTripsBatchLaunchDelay() throws Exception {
        Path file = tempDirectory.resolve("app_launcher_settings.json");
        AppLauncherSettingsStore store = new AppLauncherSettingsStore(file);

        assertEquals(AppLauncherSettingsStore.DEFAULT_LAUNCH_DELAY_MILLIS,
                store.load().launchDelayMillis());

        store.save(new Settings(5_000));

        assertEquals(5_000, store.load().launchDelayMillis());
    }

    @Test
    void rejectsOutOfRangeAndRecoversMalformedSettingsOnSave() throws Exception {
        Path file = tempDirectory.resolve("app_launcher_settings.json");
        AppLauncherSettingsStore store = new AppLauncherSettingsStore(file);

        assertThrows(java.io.IOException.class, () -> store.save(new Settings(60_001)));
        Files.writeString(file, "{\"launchDelayMillis\":\"invalid\"}");
        assertThrows(java.io.IOException.class, store::load);

        store.save(new Settings(2_000));

        assertEquals(2_000, store.load().launchDelayMillis());
    }
}
