package plugin.javafxtools.util;

import javafx.scene.Scene;
import javafx.scene.control.Dialog;

import java.net.URL;
import java.util.List;

/**
 * Applies the shared application styles to main and detached JavaFX windows.
 */
public final class FxTheme {
    private static final List<String> STYLESHEETS = List.of(
            resourceUrl("/css/styles.css"),
            resourceUrl("/css/modern-light.css")
    );

    private FxTheme() {
    }

    public static void apply(Scene scene) {
        if (scene != null) {
            scene.getStylesheets().setAll(STYLESHEETS);
        }
    }

    public static void apply(Dialog<?> dialog) {
        if (dialog != null) {
            dialog.getDialogPane().getStylesheets().setAll(STYLESHEETS);
        }
    }

    private static String resourceUrl(String path) {
        URL resource = FxTheme.class.getResource(path);
        if (resource == null) {
            throw new IllegalStateException("找不到主题资源: " + path);
        }
        return resource.toExternalForm();
    }
}
