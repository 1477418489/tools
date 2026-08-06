package plugin.javafxtools.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketControllerTest {
    @Test
    void leavesNormalDisplayMessagesUnchanged() {
        assertEquals("payload", WebSocketController.limitDisplayMessage("payload"));
        assertEquals("", WebSocketController.limitDisplayMessage(null));
    }

    @Test
    void boundsOversizedMessagesWithoutSplittingSurrogatePairs() {
        String message = "x".repeat(199_999) + "\uD83D\uDE00" + "tail";

        String limited = WebSocketController.limitDisplayMessage(message);

        assertTrue(limited.startsWith("x".repeat(199_999)));
        assertFalse(Character.isHighSurrogate(limited.charAt(199_999)));
        assertTrue(limited.contains("原始字符数: " + message.length()));
        assertTrue(limited.length() < 200_100);
    }
}
