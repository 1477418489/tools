package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SystemTrayServiceTest {
    @Test
    void unicodeSwingMenuPreservesLabelsAndActions() throws Exception {
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger exitCount = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            JPopupMenu menu = SystemTrayService.createTrayMenu(
                    openCount::incrementAndGet, exitCount::incrementAndGet);
            JMenuItem openItem = (JMenuItem) menu.getComponent(0);
            JMenuItem exitItem = (JMenuItem) menu.getComponent(2);

            assertEquals("打开 FxTools", openItem.getText());
            assertEquals("退出", exitItem.getText());
            assertFalse(menu.isLightWeightPopupEnabled());

            openItem.doClick();
            exitItem.doClick();
        });
        assertEquals(1, openCount.get());
        assertEquals(1, exitCount.get());
    }
}
