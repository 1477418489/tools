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
    private static final int MAX_SNAPSHOT_RETRIES = 3;
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");

    private final Path logFile;
    private final AttributesReader attributesReader;
    private final ChannelOpener channelOpener;
    private final boolean skipFirstObservedFile;
    private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
    private long position;
    private FileIdentity fileIdentity;
    private boolean observedFile;
    private boolean lineTruncated;

    public LogFileTailer(Path logFile, long startOffset) {
        this(logFile, startOffset, false, path -> Files.readAttributes(path, BasicFileAttributes.class),
                path -> Files.newByteChannel(path, StandardOpenOption.READ));
    }

    private LogFileTailer(Path logFile, long startOffset, boolean skipFirstObservedFile) {
        this(logFile, startOffset, skipFirstObservedFile,
                path -> Files.readAttributes(path, BasicFileAttributes.class),
                path -> Files.newByteChannel(path, StandardOpenOption.READ));
    }

    LogFileTailer(Path logFile, long startOffset, AttributesReader attributesReader) {
        this(logFile, startOffset, false, attributesReader,
                path -> Files.newByteChannel(path, StandardOpenOption.READ));
    }

    private LogFileTailer(Path logFile, long startOffset, boolean skipFirstObservedFile,
                          AttributesReader attributesReader, ChannelOpener channelOpener) {
        this.logFile = Objects.requireNonNull(logFile, "logFile");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset 不能为负数");
        }
        this.position = startOffset;
        this.skipFirstObservedFile = skipFirstObservedFile;
        this.attributesReader = Objects.requireNonNull(attributesReader, "attributesReader");
        this.channelOpener = Objects.requireNonNull(channelOpener, "channelOpener");
    }

    LogFileTailer(Path logFile, long startOffset, AttributesReader attributesReader,
                  ChannelOpener channelOpener) {
        this(logFile, startOffset, false, attributesReader, channelOpener);
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

        for (int attempt = 0; attempt < MAX_SNAPSHOT_RETRIES; attempt++) {
            BasicFileAttributes before = attributesReader.read(logFile);
            FileIdentity beforeIdentity = FileIdentity.from(before);
            boolean nextObserved = observedFile;
            FileIdentity nextIdentity = fileIdentity;
            long nextPosition = position;
            WorkingLine nextLine = new WorkingLine(pendingLine.toByteArray(), lineTruncated);

            if (!nextObserved) {
                nextObserved = true;
                nextIdentity = beforeIdentity;
                if (skipFirstObservedFile) {
                    BasicFileAttributes after = attributesReader.read(logFile);
                    if (!beforeIdentity.matches(FileIdentity.from(after))) {
                        continue;
                    }
                    commit(after.size(), nextLine.clear(), nextObserved, beforeIdentity);
                    return List.of();
                }
            } else if (!nextIdentity.matches(beforeIdentity) || before.size() < nextPosition) {
                nextPosition = 0;
                nextLine.clear();
                nextIdentity = beforeIdentity;
            }

            List<String> lines = new ArrayList<>();
            long readPosition;
            try (SeekableByteChannel channel = channelOpener.open(logFile)) {
                channel.position(Math.min(nextPosition, channel.size()));
                ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
                while (channel.read(buffer) > 0) {
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        acceptByte(buffer.get(), nextLine, lines);
                    }
                    buffer.clear();
                }
                readPosition = channel.position();
            }

            BasicFileAttributes after = attributesReader.read(logFile);
            if (!beforeIdentity.matches(FileIdentity.from(after))) {
                continue;
            }
            if (flushPartial && !nextLine.isEmpty()) {
                lines.add(finishLine(nextLine));
            }
            commit(readPosition, nextLine, nextObserved, nextIdentity);
            return List.copyOf(lines);
        }
        throw new IOException("日志文件在读取期间持续变化");
    }

    private void commit(long nextPosition, WorkingLine nextLine, boolean nextObserved,
                        FileIdentity nextIdentity) {
        position = nextPosition;
        pendingLine.reset();
        pendingLine.writeBytes(nextLine.bytes.toByteArray());
        lineTruncated = nextLine.truncated;
        observedFile = nextObserved;
        fileIdentity = nextIdentity;
    }

    private static void acceptByte(byte value, WorkingLine line, List<String> lines) {
        if (value == '\n') {
            lines.add(finishLine(line));
        } else if (value != '\r') {
            if (line.bytes.size() < MAX_LINE_BYTES) {
                line.bytes.write(value);
            } else {
                line.truncated = true;
            }
        }
    }

    private static String finishLine(WorkingLine workingLine) {
        String line = workingLine.bytes.toString(StandardCharsets.UTF_8);
        workingLine.bytes.reset();
        if (workingLine.truncated) {
            line += "... [单行日志已截断]";
            workingLine.truncated = false;
        }
        return ANSI_ESCAPE.matcher(line).replaceAll("");
    }

    @FunctionalInterface
    interface AttributesReader {
        BasicFileAttributes read(Path logFile) throws IOException;
    }

    @FunctionalInterface
    interface ChannelOpener {
        SeekableByteChannel open(Path logFile) throws IOException;
    }

    static boolean sameFile(BasicFileAttributes first, BasicFileAttributes second) {
        return FileIdentity.from(first).matches(FileIdentity.from(second));
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

    private static final class WorkingLine {
        private final ByteArrayOutputStream bytes;
        private boolean truncated;

        private WorkingLine(byte[] bytes, boolean truncated) {
            this.bytes = new ByteArrayOutputStream(bytes.length);
            this.bytes.writeBytes(bytes);
            this.truncated = truncated;
        }

        private WorkingLine clear() {
            bytes.reset();
            truncated = false;
            return this;
        }

        private boolean isEmpty() {
            return bytes.size() == 0 && !truncated;
        }
    }
}
