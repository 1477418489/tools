package plugin.javafxtools.control;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import plugin.javafxtools.model.JarProjectRuntimeStatus;
import plugin.javafxtools.model.ProjectConfig;

import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * JAR 项目列表状态单元格。
 */
public final class JarProjectStatusCell extends ListCell<ProjectConfig> {
    private final Function<ProjectConfig, JarProjectRuntimeStatus> statusResolver;
    private final ToIntFunction<ProjectConfig> portResolver;
    private final Label nameLabel = new Label();
    private final Label metaLabel = new Label();
    private final Label statusLabel = new Label();
    private final StackPane statusDot = new StackPane();
    private final VBox details = new VBox(2, nameLabel, metaLabel);
    private final HBox row = new HBox(9, statusDot, details, statusLabel);

    public JarProjectStatusCell(Function<ProjectConfig, JarProjectRuntimeStatus> statusResolver,
                                ToIntFunction<ProjectConfig> portResolver) {
        this.statusResolver = statusResolver;
        this.portResolver = portResolver;
        nameLabel.getStyleClass().add("list-item-title");
        metaLabel.getStyleClass().add("list-item-meta");
        statusLabel.getStyleClass().add("jar-project-status");
        statusDot.getStyleClass().add("jar-project-status-dot");
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(details, Priority.ALWAYS);
        statusLabel.setAlignment(Pos.CENTER_RIGHT);
        statusLabel.setMinWidth(52);
    }

    @Override
    protected void updateItem(ProjectConfig project, boolean empty) {
        super.updateItem(project, empty);
        if (empty || project == null) {
            setText(null);
            setGraphic(null);
            return;
        }
        JarProjectRuntimeStatus status = statusResolver.apply(project);
        String profile = project.getDefaultProfile() == null
                ? "" : project.getDefaultProfile().trim();
        nameLabel.setText(project.getName());
        metaLabel.setText("端口 " + portResolver.applyAsInt(project)
                + (profile.isEmpty() ? "" : " · " + profile));
        statusLabel.setText(status.label());
        row.getStyleClass().setAll("jar-project-row", status.styleClass());
        setText(null);
        setGraphic(row);
    }
}
