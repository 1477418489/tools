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
import java.nio.file.attribute.FileTime;
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
    private final AttributesReader attributesReader;
    private final boolean skipFirstObservedFile;
    private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
    private long position;
    private FileIdentity fileIdentity;
    private boolean observedFile;
    private boolean lineTruncated;

    public LogFileTailer(Path logFile, long startOffset) {
        this(logFile, startOffset, false, path -> Files.readAttributes(path, BasicFileAttributes.class));
    }

    private LogFileTailer(Path logFile, long startOffset, boolean skipFirstObservedFile) {
        this(logFile, startOffset, skipFirstObservedFile,
                path -> Files.readAttributes(path, BasicFileAttributes.class));
    }

    LogFileTailer(Path logFile, long startOffset, AttributesReader attributesReader) {
        this(logFile, startOffset, false, attributesReader);
    }

    private LogFileTailer(Path logFile, long startOffset, boolean skipFirstObservedFile,
                          AttributesReader attributesReader) {
        this.logFile = Objects.requireNonNull(logFile, "logFile");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset 不能为负数");
        }
        this.position = startOffset;
        this.skipFirstObservedFile = skipFirstObservedFile;
        this.attributesReader = Objects.requireNonNull(attributesReader, "attributesReader");
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

        BasicFileAttributes attributes = attributesReader.read(logFile);
        long size = attributes.size();
        FileIdentity currentFileIdentity = FileIdentity.from(attributes);
        if (!observedFile) {
            observedFile = true;
            fileIdentity = currentFileIdentity;
            if (skipFirstObservedFile) {
                position = size;
                clearPendingLine();
                return List.of();
            }
        } else if (!fileIdentity.matches(currentFileIdentity) || size < position) {
            position = 0;
            clearPendingLine();
            fileIdentity = currentFileIdentity;
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

    @FunctionalInterface
    interface AttributesReader {
        BasicFileAttributes read(Path logFile) throws IOException;
    }

    private record FileIdentity(Object fileKey, FileTime creationTime) {
        private static FileIdentity from(BasicFileAttributes attributes) {
            return new FileIdentity(attributes.fileKey(), attributes.creationTime());
        }

        private boolean matches(FileIdentity other) {
            if (fileKey != null || other.fileKey != null) {
                return fileKey != null && fileKey.equals(other.fileKey);
            }
            return creationTime.equals(other.creationTime);
        }
    }
}
