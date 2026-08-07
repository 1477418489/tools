package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DevEnvironmentServiceTest {
    @Test
    void extractsFirstNonBlankVersionLine() {
        assertEquals("Apache Maven 3.9.9",
                DevEnvironmentService.firstMeaningfulLine(
                        "\r\nApache Maven 3.9.9\r\nMaven home: C:\\tools"));
        assertEquals("", DevEnvironmentService.firstMeaningfulLine(" \r\n "));
        assertEquals("", DevEnvironmentService.firstMeaningfulLine(null));
    }
}
