package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import plugin.javafxtools.model.ProjectConfig;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * JAR 启动器项目配置弹窗。
 *
 * @author wwj
 */
public class JarProjectDialogService {
    private final Consumer<String> errorReporter;

    /**
     * 创建项目配置弹窗服务。
     *
     * @param errorReporter 错误提示回调
     */
    public JarProjectDialogService(Consumer<String> errorReporter) {
        this.errorReporter = errorReporter;
    }

    /**
     * 显示项目配置弹窗。
     *
     * @param project 初始项目配置
     * @return 用户保存后的项目配置
     */
    public ProjectConfig showProjectConfigDialog(ProjectConfig project) {
        Dialog<ProjectConfig> dialog = new Dialog<>();
        dialog.setTitle("项目配置");
        dialog.setHeaderText("配置项目参数");

        ButtonType saveButtonType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        ProjectConfigForm form = new ProjectConfigForm(project);
        dialog.getDialogPane().setContent(form.grid());
        Platform.runLater(() -> form.nameField().requestFocus());

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton != saveButtonType) {
                return null;
            }
            try {
                return form.toProjectConfig();
            } catch (NumberFormatException e) {
                errorReporter.accept("端口必须是数字");
                return null;
            }
        });

        Optional<ProjectConfig> result = dialog.showAndWait();
        return result.orElse(null);
    }

    private record ProjectConfigForm(
            GridPane grid,
            TextField nameField,
            TextField sourceJarField,
            TextField targetJarField,
            TextField sourceLibField,
            TextField libTargetField,
            TextField defaultPortField,
            TextField defaultProfileField,
            TextField jvmOptsField,
            TextField otherOptsField
    ) {
        private ProjectConfigForm(ProjectConfig project) {
            this(new GridPane(),
                    new TextField(project.getName()),
                    new TextField(project.getSourceJar()),
                    new TextField(project.getTargetJar()),
                    new TextField(project.getSourceLib()),
                    new TextField(project.getLibTarget()),
                    new TextField(String.valueOf(project.getDefaultPort())),
                    new TextField(project.getDefaultProfile()),
                    new TextField(project.getJvmOpts()),
                    new TextField(project.getOtherOpts()));
            setupGrid();
        }

        private void setupGrid() {
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20, 150, 10, 10));
            nameField.setPrefColumnCount(30);

            grid.add(new Label("项目名称:"), 0, 0);
            grid.add(nameField, 1, 0);
            grid.add(new Label("源JAR路径:"), 0, 1);
            grid.add(sourceJarField, 1, 1);
            grid.add(new Label("目标JAR路径:"), 0, 2);
            grid.add(targetJarField, 1, 2);
            grid.add(new Label("源Lib路径:"), 0, 3);
            grid.add(sourceLibField, 1, 3);
            grid.add(new Label("目标Lib路径:"), 0, 4);
            grid.add(libTargetField, 1, 4);
            grid.add(new Label("默认端口:"), 0, 5);
            grid.add(defaultPortField, 1, 5);
            grid.add(new Label("默认环境:"), 0, 6);
            grid.add(defaultProfileField, 1, 6);
            grid.add(new Label("JVM参数:"), 0, 7);
            grid.add(jvmOptsField, 1, 7);
            grid.add(new Label("其它项目参数:"), 0, 8);
            grid.add(otherOptsField, 1, 8);
        }

        private ProjectConfig toProjectConfig() {
            ProjectConfig newProject = new ProjectConfig();
            newProject.setName(nameField.getText());
            newProject.setSourceJar(sourceJarField.getText());
            newProject.setTargetJar(targetJarField.getText());
            newProject.setSourceLib(sourceLibField.getText());
            newProject.setLibTarget(libTargetField.getText());
            newProject.setDefaultPort(Integer.parseInt(defaultPortField.getText()));
            newProject.setDefaultProfile(defaultProfileField.getText());
            newProject.setJvmOpts(jvmOptsField.getText());
            newProject.setOtherOpts(otherOptsField.getText());
            return newProject;
        }
    }
}
