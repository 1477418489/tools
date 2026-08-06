package plugin.javafxtools.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 通过同目录临时文件和原子替换写入配置文件。
 */
public final class AtomicFileWriter {
    private AtomicFileWriter() {
    }

    /**
     * 使用 UTF-8 原子写入文本。
     *
     * @param target 目标文件
     * @param content 文件内容
     * @throws IOException 写入失败
     */
    public static void writeUtf8(Path target, String content) throws IOException {
        Objects.requireNonNull(content, "content");
        replace(target, temporaryFile ->
                Files.writeString(temporaryFile, content, StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
    }

    private static void replace(Path target, TemporaryFileWriter writer) throws IOException {
        Objects.requireNonNull(target, "target");
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("目标文件缺少父目录: " + absoluteTarget);
        }

        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent,
                "." + absoluteTarget.getFileName() + ".", ".tmp");
        try {
            writer.write(temporaryFile);
            try (FileChannel channel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveReplacing(temporaryFile, absoluteTarget);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    private interface TemporaryFileWriter {
        void write(Path temporaryFile) throws IOException;
    }
}
