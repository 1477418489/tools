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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        LogFileTailer tailer = new LogFileTailer(log, 0L, new LogFileTailer.AttributesReader() {
            private int calls;

            @Override
            public BasicFileAttributes read(Path ignored) {
                return calls++ == 0 ? before : after;
            }
        });
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

    private BasicFileAttributes attributes(long size, long createdAtMillis) {
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
                return null;
            }
        };
    }

    private void append(Path log, String value) throws Exception {
        Files.writeString(log, value, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
