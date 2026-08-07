package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProcessPortServiceTest {
    @Test
    void parsesTaskListMemoryAndQuotedImageNames() {
        var processes = ProcessPortService.parseTaskList(List.of(
                "\"java.exe\",\"1200\",\"Console\",\"1\",\"20,500 K\"",
                "\"my,app.exe\",\"1300\",\"Console\",\"1\",\"1,024 K\"",
                "INFO: No tasks are running"
        ));

        assertEquals(2, processes.size());
        assertEquals("java.exe", processes.get(1200L).name());
        assertEquals(20_500L * 1024L, processes.get(1200L).memoryBytes());
        assertEquals("my,app.exe", processes.get(1300L).name());
    }

    @Test
    void parsesOnlyTcpListenersIncludingIpv6Endpoints() {
        var ports = ProcessPortService.parseNetstat(List.of(
                "  TCP    0.0.0.0:8080       0.0.0.0:0       LISTENING       1200",
                "  TCP    [::1]:5432         [::]:0          LISTENING       1300",
                "  TCP    127.0.0.1:62000    1.1.1.1:443    ESTABLISHED     1400",
                "  UDP    0.0.0.0:5353       *:*                            1500"
        ));

        assertEquals(2, ports.size());
        assertEquals("0.0.0.0", ports.get(0).address());
        assertEquals(8080, ports.get(0).port());
        assertEquals("::1", ports.get(1).address());
        assertEquals(5432, ports.get(1).port());
        assertFalse(ports.stream().anyMatch(port -> port.pid() == 1400));
    }
}
