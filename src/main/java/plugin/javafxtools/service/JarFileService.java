package plugin.javafxtools.service;

import plugin.javafxtools.model.ProjectConfig;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
        Objects.requireNonNull(project, "project");
        Path sourceJar = requiredPath(project.getSourceJar(), "源JAR路径");
        Path targetJar = requiredPath(project.getTargetJar(), "目标JAR路径");
        if (!Files.isRegularFile(sourceJar)) {
            throw new IOException("源JAR文件不存在: " + sourceJar);
        }
        ensureDifferentFiles(sourceJar, targetJar);

        String sourceLibText = trimToEmpty(project.getSourceLib());
        String targetLibText = trimToEmpty(project.getLibTarget());
        if (sourceLibText.isEmpty() != targetLibText.isEmpty()) {
            throw new IOException("源Lib路径和目标Lib路径必须同时填写或同时留空");
        }

        Path sourceLib = null;
        Path targetLib = null;
        if (!sourceLibText.isEmpty()) {
            sourceLib = requiredPath(sourceLibText, "源Lib路径");
            targetLib = requiredPath(targetLibText, "目标Lib路径");
            validateLibDirectories(sourceLib, targetLib);
            validateCopyPlan(sourceJar, targetJar, sourceLib, targetLib);
        }

        copyJarFile(sourceJar, targetJar);
        if (sourceLib != null) {
            replaceLibDirectory(sourceLib, targetLib);
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

    private void copyJarFile(Path source, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("目标JAR路径缺少父目录: " + target);
        }
        Files.createDirectories(parent);

        Path temporaryJar = Files.createTempFile(parent,
                "." + target.getFileName() + ".", ".tmp");
        try {
            Files.copy(source, temporaryJar,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            moveReplacing(temporaryJar, target);
            logger.accept("已复制JAR文件到: " + target);
        } finally {
            Files.deleteIfExists(temporaryJar);
        }
    }

    private void replaceLibDirectory(Path source, Path target) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent,
                "." + target.getFileName() + ".staging.");
        Path backup = null;
        boolean installed = false;
        try {
            copyDirectoryContents(source, staging);
            if (Files.exists(target)) {
                backup = reserveSiblingDirectory(parent, target.getFileName() + ".backup.");
                moveDirectory(target, backup);
            }
            moveDirectory(staging, target);
            installed = true;
            logger.accept("已复制lib目录到: " + target);
        } catch (IOException installFailure) {
            if (!Files.exists(target) && backup != null && Files.exists(backup)) {
                try {
                    moveDirectory(backup, target);
                } catch (IOException rollbackFailure) {
                    installFailure.addSuppressed(rollbackFailure);
                }
            }
            throw installFailure;
        } finally {
            try {
                deleteDirectoryIfExists(staging);
            } catch (IOException e) {
                logger.accept("清理Lib临时目录失败: " + staging);
            }
            if (installed && backup != null && Files.exists(backup)) {
                try {
                    deleteDirectoryIfExists(backup);
                } catch (IOException e) {
                    logger.accept("清理Lib备份目录失败: " + backup);
                }
            }
        }
    }

    private void copyDirectoryContents(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteDirectoryIfExists(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path current, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(current);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void validateLibDirectories(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("源Lib目录不存在: " + source);
        }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (target.getParent() == null || target.equals(workingDirectory)) {
            throw new IOException("目标Lib目录不能是文件系统根目录或当前工作目录: " + target);
        }
        if (source.equals(target) || source.startsWith(target) || target.startsWith(source)) {
            throw new IOException("源Lib目录和目标Lib目录不能相同或互相包含");
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new IOException("目标Lib路径不是目录: " + target);
        }
    }

    private void validateCopyPlan(Path sourceJar, Path targetJar, Path sourceLib, Path targetLib)
            throws IOException {
        if (targetJar.startsWith(sourceLib)
                || targetJar.startsWith(targetLib)
                || sourceJar.startsWith(targetLib)) {
            throw new IOException("JAR路径不能位于会被复制或替换的Lib目录中");
        }
    }

    private void ensureDifferentFiles(Path source, Path target) throws IOException {
        if (source.equals(target) || Files.exists(target) && Files.isSameFile(source, target)) {
            throw new IOException("源JAR和目标JAR不能是同一个文件");
        }
    }

    private Path requiredPath(String value, String fieldName) throws IOException {
        String pathText = trimToEmpty(value);
        if (pathText.isEmpty()) {
            throw new IOException(fieldName + "不能为空");
        }
        Path path;
        try {
            path = Paths.get(pathText).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new IOException(fieldName + "无效: " + pathText, e);
        }
        if (path.getFileName() == null) {
            throw new IOException(fieldName + "不能是文件系统根目录: " + path);
        }
        return path;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private Path reserveSiblingDirectory(Path parent, String prefix) throws IOException {
        Path reserved = Files.createTempDirectory(parent, "." + prefix);
        Files.delete(reserved);
        return reserved;
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
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
        if (profile != null && !profile.isBlank()) {
            fullCommand.append(" --spring.profiles.active=").append(profile.trim());
        }
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
