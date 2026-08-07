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
     * @return 实际 Java 进程
     * @throws IOException 启动失败
     */
    public Process startJavaApplication(ProjectConfig project, int port, String profile) throws IOException {
        List<String> command = buildJavaArguments(project, port, profile);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Path jarPath = requiredPath(project.getTargetJar(), "目标JAR路径");
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException("目标JAR文件不存在: " + jarPath);
        }
        Path workingDirectory = resolveTargetDirectory(project);
        processBuilder.directory(workingDirectory.toFile());
        Path outputLog = resolveOutputLog(project, port);
        processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(outputLog.toFile()));
        processBuilder.redirectErrorStream(true);

        Map<String, String> env = processBuilder.environment();
        env.put("JAVA_TOOL_OPTIONS", "");

        Process process = processBuilder.start();
        process.getOutputStream().close();
        logger.accept("JAR进程已启动，PID: " + process.pid() + "，输出日志: " + outputLog);
        return process;
    }

    /**
     * 解析目标 JAR 所在目录。
     *
     * @param project 项目配置
     * @return 目标目录
     * @throws IOException 目标 JAR 路径无效
     */
    public Path resolveTargetDirectory(ProjectConfig project) throws IOException {
        if (project == null) {
            throw new IOException("项目配置不能为空");
        }
        Path targetJar = requiredPath(project.getTargetJar(), "目标JAR路径");
        Path parent = targetJar.getParent();
        if (parent == null) {
            throw new IOException("目标JAR路径缺少父目录: " + targetJar);
        }
        return parent;
    }

    /**
     * 解析指定启动端口对应的输出日志。
     *
     * @param project 项目配置
     * @param port 启动端口
     * @return 输出日志路径
     * @throws IOException 项目路径或端口无效
     */
    public Path resolveOutputLog(ProjectConfig project, int port) throws IOException {
        if (!JarPortProcessService.isValidPort(port)) {
            throw new IOException("端口必须在 1 到 65535 之间");
        }
        return resolveTargetDirectory(project)
                .resolve("jar-launcher-" + port + ".log")
                .toAbsolutePath().normalize();
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

    private List<String> buildJavaArguments(ProjectConfig project, int port, String profile)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.addAll(parseOptions(project.getJvmOpts()));
        command.add("-jar");
        command.add(requiredPath(project.getTargetJar(), "目标JAR路径").toString());
        command.add("--server.port=" + port);
        if (profile != null && !profile.isBlank()) {
            command.add("--spring.profiles.active=" + profile.trim());
        }
        command.addAll(parseOptions(project.getOtherOpts()));
        return command;
    }

    static List<String> parseOptions(String text) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean tokenStarted = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (quote == 0 && Character.isWhitespace(character)) {
                if (tokenStarted) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else if (character == '\'' || character == '"') {
                if (quote == 0) {
                    quote = character;
                    tokenStarted = true;
                } else if (quote == character) {
                    quote = 0;
                } else {
                    current.append(character);
                }
            } else if (character == '\\' && i + 1 < text.length()
                    && text.charAt(i + 1) == quote) {
                current.append(text.charAt(++i));
                tokenStarted = true;
            } else {
                current.append(character);
                tokenStarted = true;
            }
        }
        if (quote != 0) {
            throw new IOException("启动参数包含未闭合的引号");
        }
        if (tokenStarted) {
            arguments.add(current.toString());
        }
        return List.copyOf(arguments);
    }
}
