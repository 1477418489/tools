package plugin.javafxtools.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 增量读取后台 Java 进程写入的启动日志，不持有子进程输出管道。
 */
public final class JarStartupLogTailer {
    private static final int READ_BUFFER_BYTES = 8192;
    private static final int MAX_LINE_BYTES = 32 * 1024;
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");

    private final Path logFile;
    private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
    private long position;
    private boolean lineTruncated;

    public JarStartupLogTailer(Path logFile, long startOffset) {
        this.logFile = Objects.requireNonNull(logFile, "logFile");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset 不能为负数");
        }
        this.position = startOffset;
    }

    /**
     * 读取从上次调用后新增的完整日志行。
     *
     * @param flushPartial 是否把尚未换行的末尾内容作为最后一行返回
     * @return 新增日志行
     * @throws IOException 日志读取失败
     */
    public synchronized List<String> readAvailable(boolean flushPartial) throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return List.of();
        }

        long size = Files.size(logFile);
        if (size < position) {
            position = 0;
            pendingLine.reset();
            lineTruncated = false;
        }

        List<String> lines = new ArrayList<>();
        try (SeekableByteChannel channel = Files.newByteChannel(logFile, StandardOpenOption.READ)) {
            channel.position(Math.min(position, channel.size()));
            ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
            while (channel.read(buffer) > 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    acceptByte(buffer.get(), lines);
                }
                buffer.clear();
            }
            position = channel.position();
        }

        if (flushPartial && (pendingLine.size() > 0 || lineTruncated)) {
            lines.add(finishLine());
        }
        return List.copyOf(lines);
    }

    private void acceptByte(byte value, List<String> lines) {
        if (value == '\n') {
            lines.add(finishLine());
            return;
        }
        if (value == '\r') {
            return;
        }
        if (pendingLine.size() < MAX_LINE_BYTES) {
            pendingLine.write(value);
        } else {
            lineTruncated = true;
        }
    }

    private String finishLine() {
        String line = pendingLine.toString(StandardCharsets.UTF_8);
        pendingLine.reset();
        if (lineTruncated) {
            line += "... [单行日志已截断]";
            lineTruncated = false;
        }
        return ANSI_ESCAPE.matcher(line).replaceAll("");
    }
}
