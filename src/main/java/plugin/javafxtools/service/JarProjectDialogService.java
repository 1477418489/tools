package plugin.javafxtools.service;

import javafx.beans.binding.Bindings;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import plugin.javafxtools.model.ProjectConfig;
import plugin.javafxtools.util.FxTheme;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
        FxTheme.apply(dialog);
        dialog.setTitle("项目配置");
        dialog.setHeaderText("配置项目参数");

        ButtonType saveButtonType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        ProjectConfigForm form = new ProjectConfigForm(project);
        dialog.getDialogPane().setContent(form.grid());
        form.bindValidation(dialog.getDialogPane().lookupButton(saveButtonType));
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
            TextField otherOptsField,
            Label validationLabel
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
                    new TextField(project.getOtherOpts()),
                    new Label());
            setupGrid();
        }

        private void setupGrid() {
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(16));
            grid.setPrefWidth(620);
            ColumnConstraints labelColumn = new ColumnConstraints();
            ColumnConstraints inputColumn = new ColumnConstraints();
            inputColumn.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().setAll(labelColumn, inputColumn);

            nameField.setPrefColumnCount(30);
            sourceJarField.setPromptText("源构建产物路径");
            targetJarField.setPromptText("部署目标路径");
            sourceLibField.setPromptText("可选，与目标 Lib 同时填写");
            libTargetField.setPromptText("可选，与源 Lib 同时填写");
            defaultPortField.setPromptText("1 - 65535");
            defaultProfileField.setPromptText("可选");
            validationLabel.getStyleClass().addAll("feedback-text", "feedback-error");
            validationLabel.setWrapText(true);

            grid.add(new Label("项目名称 *"), 0, 0);
            grid.add(nameField, 1, 0);
            grid.add(new Label("源 JAR 路径 *"), 0, 1);
            grid.add(sourceJarField, 1, 1);
            grid.add(new Label("目标 JAR 路径 *"), 0, 2);
            grid.add(targetJarField, 1, 2);
            grid.add(new Label("源 Lib 路径"), 0, 3);
            grid.add(sourceLibField, 1, 3);
            grid.add(new Label("目标 Lib 路径"), 0, 4);
            grid.add(libTargetField, 1, 4);
            grid.add(new Label("默认端口 *"), 0, 5);
            grid.add(defaultPortField, 1, 5);
            grid.add(new Label("默认环境"), 0, 6);
            grid.add(defaultProfileField, 1, 6);
            grid.add(new Label("JVM 参数"), 0, 7);
            grid.add(jvmOptsField, 1, 7);
            grid.add(new Label("应用参数"), 0, 8);
            grid.add(otherOptsField, 1, 8);
            grid.add(validationLabel, 0, 9, 2, 1);
        }

        private void bindValidation(Node saveButton) {
            validationLabel.textProperty().bind(Bindings.createStringBinding(
                    this::validationMessage,
                    nameField.textProperty(),
                    sourceJarField.textProperty(),
                    targetJarField.textProperty(),
                    sourceLibField.textProperty(),
                    libTargetField.textProperty(),
                    defaultPortField.textProperty()));
            saveButton.disableProperty().bind(validationLabel.textProperty().isNotEmpty());
        }

        private String validationMessage() {
            if (text(nameField).isEmpty()) {
                return "请填写项目名称";
            }
            if (text(sourceJarField).isEmpty()) {
                return "请填写源 JAR 路径";
            }
            if (text(targetJarField).isEmpty()) {
                return "请填写目标 JAR 路径";
            }

            int port;
            try {
                port = Integer.parseInt(text(defaultPortField));
            } catch (NumberFormatException e) {
                return "默认端口必须是整数";
            }
            if (!JarPortProcessService.isValidPort(port)) {
                return "默认端口必须在 1 到 65535 之间";
            }

            boolean hasSourceLib = !text(sourceLibField).isEmpty();
            boolean hasTargetLib = !text(libTargetField).isEmpty();
            if (hasSourceLib != hasTargetLib) {
                return "源 Lib 路径与目标 Lib 路径需要同时填写";
            }

            String sourceJarError = absolutePathError(text(sourceJarField), "源 JAR 路径");
            if (sourceJarError != null) {
                return sourceJarError;
            }
            String targetJarError = absolutePathError(text(targetJarField), "目标 JAR 路径");
            if (targetJarError != null) {
                return targetJarError;
            }
            Path sourceJar = Path.of(text(sourceJarField)).normalize();
            Path targetJar = Path.of(text(targetJarField)).normalize();
            if (samePath(sourceJar, targetJar)) {
                return "源 JAR 与目标 JAR 路径不能相同";
            }

            if (hasSourceLib) {
                String sourceLibError = absolutePathError(text(sourceLibField), "源 Lib 路径");
                if (sourceLibError != null) {
                    return sourceLibError;
                }
                String targetLibError = absolutePathError(text(libTargetField), "目标 Lib 路径");
                if (targetLibError != null) {
                    return targetLibError;
                }
                Path sourceLib = Path.of(text(sourceLibField)).normalize();
                Path targetLib = Path.of(text(libTargetField)).normalize();
                if (samePath(sourceLib, targetLib)
                        || sourceLib.startsWith(targetLib)
                        || targetLib.startsWith(sourceLib)) {
                    return "源 Lib 与目标 Lib 路径不能相同或互相包含";
                }
                if (targetJar.startsWith(sourceLib)
                        || targetJar.startsWith(targetLib)
                        || sourceJar.startsWith(targetLib)) {
                    return "JAR 路径不能位于会被复制或替换的 Lib 目录中";
                }
            }
            return "";
        }

        private String absolutePathError(String value, String fieldName) {
            try {
                if (!Path.of(value).isAbsolute()) {
                    return fieldName + "必须是绝对路径";
                }
                return null;
            } catch (InvalidPathException e) {
                return fieldName + "无效";
            }
        }

        private boolean samePath(Path first, Path second) {
            return WindowsProcessSupport.isWindows()
                    ? first.toString().equalsIgnoreCase(second.toString())
                    : first.equals(second);
        }

        private ProjectConfig toProjectConfig() {
            ProjectConfig newProject = new ProjectConfig();
            newProject.setName(text(nameField));
            newProject.setSourceJar(text(sourceJarField));
            newProject.setTargetJar(text(targetJarField));
            newProject.setSourceLib(text(sourceLibField));
            newProject.setLibTarget(text(libTargetField));
            newProject.setDefaultPort(Integer.parseInt(text(defaultPortField)));
            newProject.setDefaultProfile(text(defaultProfileField));
            newProject.setJvmOpts(text(jvmOptsField));
            newProject.setOtherOpts(text(otherOptsField));
            return newProject;
        }

        private static String text(TextField field) {
            String value = field.getText();
            return value == null ? "" : value.trim();
        }
    }
}
