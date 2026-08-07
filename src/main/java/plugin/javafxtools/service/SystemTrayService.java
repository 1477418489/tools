package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主窗口的系统托盘入口。
 */
public final class SystemTrayService implements AutoCloseable {
    private TrayIcon trayIcon;
    private JPopupMenu trayMenu;
    private JWindow menuAnchor;

    public boolean initialize(URL iconUrl, Stage primaryStage, Runnable exitAction) {
        if (iconUrl == null || !SystemTray.isSupported()) {
            return false;
        }
        try {
            java.awt.Image image = ImageIO.read(iconUrl);
            if (image == null) {
                return false;
            }
            AtomicReference<Exception> failure = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> {
                try {
                    applySystemLookAndFeel();
                    Runnable openAction = () -> Platform.runLater(() -> show(primaryStage));
                    trayMenu = createTrayMenu(openAction,
                            () -> Platform.runLater(exitAction));

                    TrayIcon icon = new TrayIcon(image, "FxTools");
                    icon.setImageAutoSize(true);
                    icon.addActionListener(event -> openAction.run());
                    icon.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseReleased(MouseEvent event) {
                            if (SwingUtilities.isRightMouseButton(event)) {
                                showTrayMenu(event.getXOnScreen(), event.getYOnScreen());
                            }
                        }
                    });
                    SystemTray.getSystemTray().add(icon);
                    trayIcon = icon;
                } catch (AWTException e) {
                    trayMenu = null;
                    failure.set(e);
                }
            });
            return failure.get() == null && trayIcon != null;
        } catch (IOException | InterruptedException | java.lang.reflect.InvocationTargetException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public void show(Stage stage) {
        stage.show();
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    static JPopupMenu createTrayMenu(Runnable openAction, Runnable exitAction) {
        JPopupMenu menu = new JPopupMenu();
        menu.setLightWeightPopupEnabled(false);

        JMenuItem openItem = new JMenuItem("打开 FxTools");
        JMenuItem exitItem = new JMenuItem("退出");
        openItem.addActionListener(event -> openAction.run());
        exitItem.addActionListener(event -> exitAction.run());
        menu.add(openItem);
        menu.addSeparator();
        menu.add(exitItem);
        return menu;
    }

    private void showTrayMenu(int screenX, int screenY) {
        if (trayMenu == null) {
            return;
        }
        hideTrayMenu();

        JWindow anchor = new JWindow();
        anchor.setAlwaysOnTop(true);
        anchor.setType(Window.Type.POPUP);
        anchor.setSize(1, 1);
        anchor.setLocation(screenX, screenY);
        anchor.setVisible(true);
        menuAnchor = anchor;

        Dimension menuSize = trayMenu.getPreferredSize();
        Rectangle screen = usableScreenBoundsAt(screenX, screenY);
        int menuX = clamp(screenX, screen.x,
                screen.x + screen.width - menuSize.width);
        int menuY = clamp(screenY - menuSize.height, screen.y,
                screen.y + screen.height - menuSize.height);

        PopupMenuListener anchorDisposer = new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                disposeMenuAnchor();
                trayMenu.removePopupMenuListener(this);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
                disposeMenuAnchor();
                trayMenu.removePopupMenuListener(this);
            }
        };
        trayMenu.addPopupMenuListener(anchorDisposer);
        trayMenu.setInvoker(anchor.getContentPane());
        trayMenu.setLocation(menuX, menuY);
        trayMenu.setVisible(true);
    }

    private void hideTrayMenu() {
        if (trayMenu != null && trayMenu.isVisible()) {
            trayMenu.setVisible(false);
        }
        disposeMenuAnchor();
    }

    private void disposeMenuAnchor() {
        JWindow anchor = menuAnchor;
        menuAnchor = null;
        if (anchor != null) {
            anchor.dispose();
        }
    }

    private static Rectangle usableScreenBoundsAt(int x, int y) {
        for (GraphicsDevice device : GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            Rectangle bounds = configuration.getBounds();
            if (bounds.contains(x, y)) {
                Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
                return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                        bounds.width - insets.left - insets.right,
                        bounds.height - insets.top - insets.bottom);
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
    }

    private static void applySystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) {
            // Swing 默认外观仍可完整渲染 Unicode 菜单文本。
        }
    }

    @Override
    public void close() {
        TrayIcon icon = trayIcon;
        trayIcon = null;
        if (icon != null || trayMenu != null) {
            EventQueue.invokeLater(() -> {
                hideTrayMenu();
                trayMenu = null;
                if (icon != null) {
                    SystemTray.getSystemTray().remove(icon);
                }
            });
        }
    }
}
