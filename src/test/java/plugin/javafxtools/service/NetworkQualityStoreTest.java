package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.service.NetworkQualityService.Protocol;
import plugin.javafxtools.service.NetworkQualityService.ProxyType;
import plugin.javafxtools.service.NetworkQualityService.RoutePlan;
import plugin.javafxtools.service.NetworkQualityService.Target;
import plugin.javafxtools.service.NetworkQualitySettingsStore.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkQualityStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void targetStoreRoundTripsCurrentSchema() throws Exception {
        NetworkQualityTargetStore store = new NetworkQualityTargetStore(
                tempDirectory.resolve("targets.json"));
        List<Target> targets = List.of(
                new Target("udp", "UDP", Protocol.STUN_UDP,
                        "stun.example.com", 3478, true),
                new Target("http", "HTTP", Protocol.HTTP,
                        "example.com", 8080, "/health?full=true", false));

        store.save(targets);

        assertEquals(targets, store.load());
    }

    @Test
    void targetStoreMigratesLegacyTargetsWithoutRequestPath() throws Exception {
        Path file = tempDirectory.resolve("legacy-targets.json");
        Files.writeString(file, """
                [{"id":"legacy","name":"Legacy TCP","protocol":"TCP",\
                "host":"example.com","port":443,"enabled":true}]
                """, StandardCharsets.UTF_8);

        List<Target> targets = new NetworkQualityTargetStore(file).load();

        assertEquals(1, targets.size());
        assertEquals("/", targets.getFirst().requestTarget());
    }

    @Test
    void targetStoreRejectsDuplicatesAndBacksUpInvalidFileBeforeRecovery() throws Exception {
        Path file = tempDirectory.resolve("targets.json");
        NetworkQualityTargetStore store = new NetworkQualityTargetStore(file);
        assertThrows(java.io.IOException.class, () -> store.save(List.of(
                new Target("one", "一", Protocol.TCP, "EXAMPLE.com", 443, true),
                new Target("two", "二", Protocol.TCP, "example.com", 443, true))));

        String invalid = "[{\"id\":\"old\"}]";
        Files.writeString(file, invalid, StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, store::load);
        List<Target> recovered = List.of(
                new Target("valid", "有效", Protocol.TCP, "example.org", 443, true));
        store.save(recovered);

        assertEquals(recovered, store.load());
        assertEquals(invalid, Files.readString(
                tempDirectory.resolve("targets.json.invalid.bak"), StandardCharsets.UTF_8));
    }

    @Test
    void settingsStoreNeverPersistsProxyPassword() throws Exception {
        Path file = tempDirectory.resolve("settings.json");
        NetworkQualitySettingsStore store = new NetworkQualitySettingsStore(file);
        Settings settings = new Settings(RoutePlan.COMPARE, ProxyType.SOCKS5,
                "127.0.0.1", 1080, "alice", 2_000, 3_000);

        store.save(settings);

        assertEquals(settings, store.load());
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(json.toLowerCase().contains("password"));
        assertFalse(json.contains("secret"));
    }

    @Test
    void settingsStoreBacksUpInvalidFileAndAllowsRecovery() throws Exception {
        Path file = tempDirectory.resolve("settings.json");
        String invalid = "{\"routePlan\":\"old-format\"}";
        Files.writeString(file, invalid, StandardCharsets.UTF_8);
        NetworkQualitySettingsStore store = new NetworkQualitySettingsStore(file);
        assertThrows(java.io.IOException.class, store::load);

        Settings recovered = Settings.defaults();
        store.save(recovered);

        assertEquals(recovered, store.load());
        assertEquals(invalid, Files.readString(
                tempDirectory.resolve("settings.json.invalid.bak"), StandardCharsets.UTF_8));
    }

    @Test
    void defaultSettingsAreSystemRoutedAndDefaultsContainAllProtocols() throws Exception {
        NetworkQualitySettingsStore settingsStore = new NetworkQualitySettingsStore(
                tempDirectory.resolve("missing-settings.json"));
        NetworkQualityTargetStore targetStore = new NetworkQualityTargetStore(
                tempDirectory.resolve("missing-targets.json"));

        assertEquals(RoutePlan.SYSTEM_ONLY, settingsStore.load().routePlan());
        List<Target> defaults = targetStore.load();
        assertTrue(defaults.stream().anyMatch(target -> target.protocol() == Protocol.HTTP));
        assertTrue(defaults.stream().anyMatch(target -> target.protocol() == Protocol.HTTPS));
        assertTrue(defaults.stream().anyMatch(target -> target.protocol() == Protocol.STUN_UDP));
        assertTrue(defaults.stream().anyMatch(target -> target.protocol() == Protocol.TCP));
        assertTrue(defaults.stream().anyMatch(target -> target.protocol() == Protocol.TLS));
    }
}
