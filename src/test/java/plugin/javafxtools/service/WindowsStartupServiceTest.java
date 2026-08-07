package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsStartupServiceTest {
    @Test
    void simpleArgumentDoesNotNeedQuotes() {
        assertEquals("--module=plugin.javafxtools",
                WindowsStartupService.quoteArgument("--module=plugin.javafxtools"));
    }

    @Test
    void pathWithSpacesIsQuoted() {
        assertEquals("\"C:\\Program Files\\FxTools\\app.exe\"",
                WindowsStartupService.quoteArgument("C:\\Program Files\\FxTools\\app.exe"));
    }

    @Test
    void trailingBackslashIsEscapedInsideQuotes() {
        assertEquals("\"C:\\Program Files\\FxTools\\\\\"",
                WindowsStartupService.quoteArgument("C:\\Program Files\\FxTools\\"));
    }
}
