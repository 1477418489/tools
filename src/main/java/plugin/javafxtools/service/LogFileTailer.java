package plugin.javafxtools.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Incrementally reads complete UTF-8 lines appended to a log file. */
public final class LogFileTailer {
    private static final int READ_BUFFER_BYTES = 8192;
    private static final int MAX_LINE_BYTES = 32 * 1024;
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");

    private final Path logFile;
    private final boolean skipFirstObservedFile;
    private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
    private long position;
    private Object fileKey;
    private boolean observedFile;
    private boolean lineTruncated;

    public LogFileTailer(Path logFile, long startOffset) {
        this(logFile, startOffset, false);
    }

    private LogFileTailer(Path logFile, long startOffset, boolean skipFirstObservedFile) {
        this.logFile = Objects.requireNonNull(logFile, "logFile");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset 不能为负数");
        }
        this.position = startOffset;
        this.skipFirstObservedFile = skipFirstObservedFile;
    }

    /** Returns a tailer that starts after content present on its first file observation. */
    public static LogFileTailer followNewContent(Path logFile) {
        return new LogFileTailer(logFile, 0L, true);
    }

    /**
     * Reads complete lines added since the last call.
     *
     * @param flushPartial whether to return an unterminated final line
     */
    public synchronized List<String> readAvailable(boolean flushPartial) throws IOException {
        if (!Files.isRegularFile(logFile)) {
            return List.of();
        }

        BasicFileAttributes attributes = Files.readAttributes(logFile, BasicFileAttributes.class);
        long size = attributes.size();
        Object currentFileKey = attributes.fileKey();
        if (!observedFile) {
            observedFile = true;
            fileKey = currentFileKey;
            if (skipFirstObservedFile) {
                position = size;
                clearPendingLine();
                return List.of();
            }
        } else if (!Objects.equals(fileKey, currentFileKey) || size < position) {
            position = 0;
            clearPendingLine();
            fileKey = currentFileKey;
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
        } else if (value != '\r') {
            if (pendingLine.size() < MAX_LINE_BYTES) {
                pendingLine.write(value);
            } else {
                lineTruncated = true;
            }
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

    private void clearPendingLine() {
        pendingLine.reset();
        lineTruncated = false;
    }
}
