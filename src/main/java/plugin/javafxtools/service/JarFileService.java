package plugin.javafxtools.service;

import plugin.javafxtools.model.ProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * JAR 启动器文件复制和 Java 应用启动命令。
 *
 * @author wwj
 */
public class JarFileService {
    private final Consumer<String> logger;

    /**
     * 创建 JAR 文件服务。
     *
     * @param logger 日志输出回调
     */
    public JarFileService(Consumer<String> logger) {
        this.logger = logger;
    }

    /**
     * 复制项目 JAR 和可选 lib 目录。
     *
     * @param project 项目配置
     * @throws IOException 文件复制异常
     */
    public void copyProjectFiles(ProjectConfig project) throws IOException {
        copyJarFile(project);
        if (Files.exists(Paths.get(project.getSourceLib()))) {
            copyLibDirectory(project);
        }
    }

    /**
     * 启动 Java 应用。
     *
     * @param project 项目配置
     * @param port 启动端口
     * @param profile Spring profile
     * @return 启动包装进程
     * @throws IOException 启动失败
     */
    public Process startJavaApplication(ProjectConfig project, int port, String profile) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("cmd.exe");
        command.add("/c");
        command.add("start");
        command.add("\"JAR_" + port + "\"");
        command.add("cmd.exe");
        command.add("/c");
        command.add(buildJavaCommand(project, port, profile));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Path jarPath = Paths.get(project.getTargetJar());
        if (jarPath.getParent() != null) {
            processBuilder.directory(jarPath.getParent().toFile());
        }

        processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

        Map<String, String> env = processBuilder.environment();
        env.put("JAVA_TOOL_OPTIONS", "");

        Process process = processBuilder.start();
        closeProcessStreams(process);
        logger.accept("已启动独立进程，PID: " + process.pid() + "，启动指令: " + processBuilder.command());
        return process;
    }

    private void copyJarFile(ProjectConfig project) throws IOException {
        Path source = Paths.get(project.getSourceJar());
        Path target = Paths.get(project.getTargetJar());

        if (!Files.exists(target.getParent())) {
            Files.createDirectories(target.getParent());
        }

        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        logger.accept("已复制JAR文件到: " + target);
    }

    private void copyLibDirectory(ProjectConfig project) throws IOException {
        Path source = Paths.get(project.getSourceLib());
        Path target = Paths.get(project.getLibTarget());

        if (Files.exists(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                logger.accept("删除旧lib文件失败: " + path);
                            }
                        });
            }
        }

        if (Files.exists(source)) {
            try (Stream<Path> walk = Files.walk(source)) {
                walk.forEach(sourcePath -> {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    try {
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else {
                            Files.copy(sourcePath, targetPath);
                        }
                    } catch (IOException e) {
                        logger.accept("复制失败: " + sourcePath + " -> " + targetPath);
                    }
                });
            }
            logger.accept("已复制lib目录到: " + target);
        }
    }

    private String buildJavaCommand(ProjectConfig project, int port, String profile) {
        StringBuilder fullCommand = new StringBuilder();
        fullCommand.append("chcp 65001 >nul && ");
        fullCommand.append("java");
        fullCommand.append(" -Dfile.encoding=UTF-8");
        fullCommand.append(" -Dsun.stdout.encoding=UTF-8");
        fullCommand.append(" -Dsun.stderr.encoding=UTF-8");

        if (project.getJvmOpts() != null && !project.getJvmOpts().isEmpty()) {
            fullCommand.append(" ").append(project.getJvmOpts());
        }

        fullCommand.append(" -jar \"").append(project.getTargetJar()).append("\"");
        fullCommand.append(" --server.port=").append(port);
        fullCommand.append(" --spring.profiles.active=").append(profile);
        String otherOpts = project.getOtherOpts();
        if (otherOpts != null && !otherOpts.trim().isEmpty()) {
            fullCommand.append(" ").append(otherOpts.trim());
        }
        return fullCommand.toString();
    }

    private void closeProcessStreams(Process process) {
        try {
            Thread.sleep(100);
            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();
        } catch (Exception e) {
            // Ignore stream close failures after starting a detached window.
        }
    }
}
