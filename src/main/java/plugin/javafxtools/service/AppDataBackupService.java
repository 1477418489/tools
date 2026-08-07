package plugin.javafxtools.service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 将当前应用数据目录导出为可恢复的 ZIP 快照。
 */
public final class AppDataBackupService {
    private static final int COPY_BUFFER_SIZE = 16 * 1024;

    /**
     * 创建数据备份。只导出常规文件，不跟随符号链接。
     *
     * @param sourceDirectory 应用数据目录
     * @param destination 目标 ZIP 文件
     * @return 备份结果
     * @throws IOException 路径无效或备份写入失败
     */
    public BackupResult export(Path sourceDirectory, Path destination) throws IOException {
        if (sourceDirectory == null || destination == null) {
            throw new IOException("备份源目录和目标文件不能为空");
        }

        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path requestedDestination = destination.toAbsolutePath().normalize();
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("应用数据目录不存在: " + source);
        }
        if (requestedDestination.startsWith(source)) {
            throw new IOException("备份文件不能保存在应用数据目录内");
        }
        if (requestedDestination.getFileName() == null) {
            throw new IOException("备份目标必须是 ZIP 文件");
        }

        Path parent = requestedDestination.getParent();
        if (parent == null) {
            throw new IOException("备份目标缺少父目录");
        }
        Files.createDirectories(parent);

        Path sourceReal = source.toRealPath();
        Path destinationReal = parent.toRealPath().resolve(requestedDestination.getFileName());
        if (destinationReal.startsWith(sourceReal)) {
            throw new IOException("备份文件不能保存在应用数据目录内");
        }
        if (Files.isSymbolicLink(destinationReal)) {
            throw new IOException("备份目标不能是符号链接");
        }
        if (Files.exists(destinationReal) && Files.isDirectory(destinationReal)) {
            throw new IOException("备份目标不能是目录: " + destinationReal);
        }

        List<Path> files = listRegularFiles(sourceReal);
        Path temporary = Files.createTempFile(parent, ".fxtools-backup-", ".tmp");
        long totalBytes = 0;
        try {
            totalBytes = writeArchive(sourceReal, files, temporary);
            moveReplacing(temporary, destinationReal);
            return new BackupResult(destinationReal, files.size(), totalBytes);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private List<Path> listRegularFiles(Path source) throws IOException {
        try (var paths = Files.walk(source)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
        }
    }

    private long writeArchive(Path source, List<Path> files, Path temporary) throws IOException {
        long totalBytes = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporary)))) {
            for (Path file : files) {
                checkInterrupted();
                String entryName = source.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        checkInterrupted();
                        zip.write(buffer, 0, read);
                        totalBytes += read;
                    }
                } finally {
                    zip.closeEntry();
                }
            }
        }
        return totalBytes;
    }

    private void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("数据备份已取消");
        }
    }

    private void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * @param archive 生成的 ZIP 文件
     * @param fileCount 已导出的文件数
     * @param uncompressedBytes 文件原始总字节数
     */
    public record BackupResult(Path archive, int fileCount, long uncompressedBytes) {
    }
}
