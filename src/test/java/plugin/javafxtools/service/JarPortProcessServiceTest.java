package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarPortProcessServiceTest {
    @Test
    void validatesPortRange() {
        assertTrue(JarPortProcessService.isValidPort(1));
        assertTrue(JarPortProcessService.isValidPort(65535));
        assertFalse(JarPortProcessService.isValidPort(0));
        assertFalse(JarPortProcessService.isValidPort(65536));
    }

    @Test
    void formatProcessDetailsIncludesNameAndCommandLineWhenAvailable() {
        List<String> wmicLines = List.of(
                "",
                "CommandLine=java -jar D:\\apps\\demo.jar --server.port=18080",
                "Name=java.exe",
                ""
        );

        String details = JarPortProcessService.formatProcessDetails("1234", wmicLines);

        assertEquals("PID:1234，进程名: java.exe，命令行: java -jar D:\\apps\\demo.jar --server.port=18080", details);
    }

    @Test
    void formatProcessDetailsFallsBackToPidWhenDetailsAreUnavailable() {
        String details = JarPortProcessService.formatProcessDetails("1234", List.of("", "CommandLine=", "Name="));

        assertEquals("PID:1234", details);
    }
}
