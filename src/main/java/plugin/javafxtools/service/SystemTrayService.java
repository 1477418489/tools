package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主窗口的系统托盘入口。
 */
public final class SystemTrayService implements AutoCloseable {
    private TrayIcon trayIcon;

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
                    PopupMenu menu = new PopupMenu();
                    MenuItem openItem = new MenuItem("打开 FxTools");
                    MenuItem exitItem = new MenuItem("退出");
                    Runnable openAction = () -> Platform.runLater(() -> show(primaryStage));
                    openItem.addActionListener(event -> openAction.run());
                    exitItem.addActionListener(event -> Platform.runLater(exitAction));
                    menu.add(openItem);
                    menu.addSeparator();
                    menu.add(exitItem);

                    TrayIcon icon = new TrayIcon(image, "FxTools", menu);
                    icon.setImageAutoSize(true);
                    icon.addActionListener(event -> openAction.run());
                    SystemTray.getSystemTray().add(icon);
                    trayIcon = icon;
                } catch (AWTException e) {
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

    @Override
    public void close() {
        TrayIcon icon = trayIcon;
        trayIcon = null;
        if (icon != null) {
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(icon));
        }
    }
}
