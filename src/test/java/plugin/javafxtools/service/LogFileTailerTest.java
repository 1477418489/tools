package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogFileTailerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void followNewContentIgnoresExistingHistoryAndReadsAppendedCompleteLines() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "old line\n", StandardCharsets.UTF_8);
        LogFileTailer tailer = LogFileTailer.followNewContent(log);

        assertEquals(List.of(), tailer.readAvailable(false));
        append(log, "new one\nnew two\n");

        assertEquals(List.of("new one", "new two"), tailer.readAvailable(false));
    }

    @Test
    void preservesSplitUtf8AndNormalizesCrLf() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        LogFileTailer tailer = new LogFileTailer(log, 0L);
        byte[] bytes = "应用启动\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(log, java.util.Arrays.copyOf(bytes, 2));

        assertEquals(List.of(), tailer.readAvailable(false));
        Files.write(log, java.util.Arrays.copyOfRange(bytes, 2, bytes.length), StandardOpenOption.APPEND);

        assertEquals(List.of("应用启动"), tailer.readAvailable(false));
    }

    @Test
    void removesAnsiSequencesAndTruncatesLongLines() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        LogFileTailer tailer = new LogFileTailer(log, 0L);
        String longLine = "x".repeat(32 * 1024 + 1);
        Files.writeString(log, "\u001B[32mgreen\u001B[0m\n" + longLine + "\n", StandardCharsets.UTF_8);

        assertEquals(List.of("green", "x".repeat(32 * 1024) + "... [单行日志已截断]"), tailer.readAvailable(false));
    }

    @Test
    void followerSkipsInitialContentsWhenFileAppearsLater() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        LogFileTailer tailer = LogFileTailer.followNewContent(log);

        assertEquals(List.of(), tailer.readAvailable(false));
        Files.writeString(log, "initial contents\n", StandardCharsets.UTF_8);
        assertEquals(List.of(), tailer.readAvailable(false));
        append(log, "later\n");

        assertEquals(List.of("later"), tailer.readAvailable(false));
    }

    @Test
    void resetsAfterTruncation() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "before\n", StandardCharsets.UTF_8);
        LogFileTailer tailer = new LogFileTailer(log, 0L);
        assertEquals(List.of("before"), tailer.readAvailable(false));

        Files.writeString(log, "after\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

        assertEquals(List.of("after"), tailer.readAvailable(false));
    }

    @Test
    void resetsAfterReplacingSamePathWithoutWaiting() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "before\n", StandardCharsets.UTF_8);
        LogFileTailer tailer = new LogFileTailer(log, 0L);
        assertEquals(List.of("before"), tailer.readAvailable(false));
        Path replacement = tempDirectory.resolve("replacement.log");
        Files.writeString(replacement, "after\n", StandardCharsets.UTF_8);
        Files.move(replacement, log, StandardCopyOption.REPLACE_EXISTING);

        assertEquals(List.of("after"), tailer.readAvailable(false));
    }

    @Test
    void resetsWhenNullFileKeysHaveDifferentCreationTimes() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "before\n", StandardCharsets.UTF_8);
        BasicFileAttributes before = attributes(7, 1);
        BasicFileAttributes after = attributes(7, 2);
        LogFileTailer tailer = new LogFileTailer(log, 0L, reader(before, before, after, after));
        assertEquals(List.of("before"), tailer.readAvailable(false));
        Files.writeString(log, "after\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

        assertEquals(List.of("after"), tailer.readAvailable(false));
    }

    @Test
    void appendsWhenNullFileKeysHaveTheSameCreationTime() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "before\n", StandardCharsets.UTF_8);
        BasicFileAttributes attributes = attributes(7, 1);
        LogFileTailer tailer = new LogFileTailer(log, 0L, ignored -> attributes);
        assertEquals(List.of("before"), tailer.readAvailable(false));
        append(log, "append\n");

        assertEquals(List.of("append"), tailer.readAvailable(false));
    }

    @Test
    void retriesFromBeginningWhenPathChangesBetweenIdentityAndOpen() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "present", StandardCharsets.UTF_8);
        BasicFileAttributes fileA = attributes(4, 1, "a");
        BasicFileAttributes fileB = attributes(4, 2, "b");
        LogFileTailer tailer = new LogFileTailer(log, 0L,
                reader(fileA, fileA, fileA, fileB, fileB, fileB),
                opener(channel("old\n"), channel("new\n"), channel("new\n")));

        assertEquals(List.of("old"), tailer.readAvailable(false));
        assertEquals(List.of("new"), tailer.readAvailable(false));
    }

    @Test
    void leavesPartialStateUntouchedWhenReadFails() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "present", StandardCharsets.UTF_8);
        BasicFileAttributes attributes = attributes(5, 1, "a");
        LogFileTailer tailer = new LogFileTailer(log, 0L, reader(attributes),
                opener(channelThenFail("once"), channel("once\n")));

        assertThrows(IOException.class, () -> tailer.readAvailable(false));
        assertEquals(List.of("once"), tailer.readAvailable(false));
    }

    @Test
    void leavesPositionUntouchedWhenCloseFails() throws Exception {
        Path log = tempDirectory.resolve("application.log");
        Files.writeString(log, "present", StandardCharsets.UTF_8);
        BasicFileAttributes attributes = attributes(5, 1, "a");
        LogFileTailer tailer = new LogFileTailer(log, 0L, reader(attributes),
                opener(channelWithCloseFailure("once\n"), channel("once\n")));

        assertThrows(IOException.class, () -> tailer.readAvailable(false));
        assertEquals(List.of("once"), tailer.readAvailable(false));
    }

    @Test
    void treatsMixedFileKeyAvailabilityAsReplacement() {
        assertEquals(false, LogFileTailer.sameFile(attributes(1, 1, "key"), attributes(1, 1)));
        assertEquals(false, LogFileTailer.sameFile(attributes(1, 1), attributes(1, 1, "key")));
    }

    private LogFileTailer.AttributesReader reader(BasicFileAttributes... attributes) {
        return new LogFileTailer.AttributesReader() {
            private int index;

            @Override
            public BasicFileAttributes read(Path ignored) {
                return attributes[Math.min(index++, attributes.length - 1)];
            }
        };
    }

    private LogFileTailer.ChannelOpener opener(SeekableByteChannel... channels) {
        return new LogFileTailer.ChannelOpener() {
            private int index;

            @Override
            public SeekableByteChannel open(Path ignored) {
                return channels[index++];
            }
        };
    }

    private SeekableByteChannel channel(String text) {
        return new ScriptedChannel(text.getBytes(StandardCharsets.UTF_8), false, false);
    }

    private SeekableByteChannel channelThenFail(String text) {
        return new ScriptedChannel(text.getBytes(StandardCharsets.UTF_8), true, false);
    }

    private SeekableByteChannel channelWithCloseFailure(String text) {
        return new ScriptedChannel(text.getBytes(StandardCharsets.UTF_8), false, true);
    }

    private BasicFileAttributes attributes(long size, long createdAtMillis) {
        return attributes(size, createdAtMillis, null);
    }

    private BasicFileAttributes attributes(long size, long createdAtMillis, Object fileKey) {
        return new BasicFileAttributes() {
            private final FileTime creationTime = FileTime.fromMillis(createdAtMillis);

            @Override
            public FileTime lastModifiedTime() {
                return creationTime;
            }

            @Override
            public FileTime lastAccessTime() {
                return creationTime;
            }

            @Override
            public FileTime creationTime() {
                return creationTime;
            }

            @Override
            public boolean isRegularFile() {
                return true;
            }

            @Override
            public boolean isDirectory() {
                return false;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return false;
            }

            @Override
            public long size() {
                return size;
            }

            @Override
            public Object fileKey() {
                return fileKey;
            }
        };
    }

    private static final class ScriptedChannel implements SeekableByteChannel {
        private final byte[] bytes;
        private final boolean failAfterContent;
        private final boolean failOnClose;
        private int position;
        private boolean contentRead;

        private ScriptedChannel(byte[] bytes, boolean failAfterContent, boolean failOnClose) {
            this.bytes = bytes;
            this.failAfterContent = failAfterContent;
            this.failOnClose = failOnClose;
        }

        @Override public int read(ByteBuffer target) throws IOException {
            if (contentRead && failAfterContent) throw new IOException("read failure");
            if (position == bytes.length) return -1;
            int count = Math.min(target.remaining(), bytes.length - position);
            target.put(bytes, position, count);
            position += count;
            contentRead = true;
            return count;
        }
        @Override public int write(ByteBuffer source) { throw new UnsupportedOperationException(); }
        @Override public long position() { return position; }
        @Override public SeekableByteChannel position(long value) { position = (int) value; return this; }
        @Override public long size() { return bytes.length; }
        @Override public SeekableByteChannel truncate(long size) { throw new UnsupportedOperationException(); }
        @Override public boolean isOpen() { return true; }
        @Override public void close() throws IOException { if (failOnClose) throw new IOException("close failure"); }
    }

    private void append(Path log, String value) throws Exception {
        Files.writeString(log, value, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
