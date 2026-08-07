package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.model.AppSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppSettingsStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void missingFileUsesCurrentDefaults() throws Exception {
        AppSettingsStore store = new AppSettingsStore(tempDirectory.resolve("settings.json"));

        assertEquals(AppSettings.defaults(), store.load());
    }

    @Test
    void settingsRoundTripUsesCurrentSchema() throws Exception {
        AppSettingsStore store = new AppSettingsStore(tempDirectory.resolve("settings.json"));
        AppSettings settings = new AppSettings(false, true, true);

        store.save(settings);

        assertEquals(settings, store.load());
    }

    @Test
    void incompleteSettingsAreRejected() throws Exception {
        Path file = tempDirectory.resolve("settings.json");
        Files.writeString(file, "{\"closeToTray\":true}");

        assertThrows(IOException.class, () -> new AppSettingsStore(file).load());
    }
}
