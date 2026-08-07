package plugin.javafxtools.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.javafxtools.service.FileAnalysisService.FileAnalysis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAnalysisServiceTest {
    @TempDir
    Path tempDirectory;

    private final FileAnalysisService service = new FileAnalysisService();

    @Test
    void calculatesKnownHashesWithoutChangingTheFile() throws Exception {
        Path file = tempDirectory.resolve("hello.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        var modifiedBefore = Files.getLastModifiedTime(file);

        FileAnalysis result = service.analyze(file);

        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                result.sha256());
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", result.sha1());
        assertEquals("5d41402abc4b2a76b9719d911017c592", result.md5());
        assertEquals(5, result.size());
        assertEquals(modifiedBefore, Files.getLastModifiedTime(file));
        assertTrue(result.readable());
    }

    @Test
    void identifiesBomUtf8Utf16HeuristicsAndBinarySamples() {
        assertEquals("UTF-8（BOM）",
                FileAnalysisService.detectEncoding(new byte[] {
                        (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'}).name());
        assertEquals("UTF-8",
                FileAnalysisService.detectEncoding("中文内容".getBytes(StandardCharsets.UTF_8)).name());
        assertEquals("UTF-16 LE（推测）",
                FileAnalysisService.detectEncoding("plain text"
                        .getBytes(StandardCharsets.UTF_16LE)).name());
        assertEquals("二进制",
                FileAnalysisService.detectEncoding(new byte[] {0, 1, 2, 3, 0, 4, 5, 6}).name());
        assertEquals("二进制",
                FileAnalysisService.detectEncoding(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}).name());
    }

    @Test
    void asciiDetectionDoesNotClaimAnExactLegacyEncoding() {
        var guess = FileAnalysisService.detectEncoding("plain".getBytes(StandardCharsets.US_ASCII));

        assertEquals("ASCII / UTF-8", guess.name());
        assertFalse(guess.detail().isBlank());
    }

    @Test
    void recognizesUtf8WhenTheBoundedSampleEndsInsideAMultibyteCharacter() throws Exception {
        byte[] content = new byte[1024 * 1024 + 4];
        java.util.Arrays.fill(content, 0, 1024 * 1024 - 1, (byte) 'a');
        byte[] chinese = "中".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(chinese, 0, content, 1024 * 1024 - 1, chinese.length);
        content[content.length - 2] = '\n';
        content[content.length - 1] = 'x';
        Path file = tempDirectory.resolve("sample-boundary.txt");
        Files.write(file, content);

        FileAnalysis result = service.analyze(file);

        assertEquals("UTF-8", result.encoding());
    }
}
