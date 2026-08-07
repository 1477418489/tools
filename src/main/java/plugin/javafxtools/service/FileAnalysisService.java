package plugin.javafxtools.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/** Calculates file hashes and provides bounded encoding and lock diagnostics. */
public final class FileAnalysisService {
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final int SAMPLE_LIMIT = 1024 * 1024;

    public FileAnalysis analyze(Path file) throws IOException {
        Path normalized = validate(file);
        BasicFileAttributes attributesBefore = Files.readAttributes(
                normalized, BasicFileAttributes.class);
        long startedAt = System.nanoTime();
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest sha1 = digest("SHA-1");
        MessageDigest md5 = digest("MD5");
        ByteArrayOutputStream sample = new ByteArrayOutputStream();

        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(normalized)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("文件分析已取消");
                }
                sha256.update(buffer, 0, read);
                sha1.update(buffer, 0, read);
                md5.update(buffer, 0, read);
                int sampleRemaining = SAMPLE_LIMIT - sample.size();
                if (sampleRemaining > 0) {
                    sample.write(buffer, 0, Math.min(read, sampleRemaining));
                }
            }
        }

        BasicFileAttributes attributesAfter = Files.readAttributes(
                normalized, BasicFileAttributes.class);
        if (attributesBefore.size() != attributesAfter.size()
                || !attributesBefore.lastModifiedTime().equals(attributesAfter.lastModifiedTime())
                || fileIdentityChanged(attributesBefore, attributesAfter)) {
            throw new IOException("文件在分析过程中发生变化，请重新分析");
        }
        byte[] sampleBytes = sample.toByteArray();
        EncodingGuess encoding = detectEncoding(
                sampleBytes, attributesAfter.size() > sampleBytes.length);
        LockInspection lock = inspectLock(normalized);
        String contentType = Files.probeContentType(normalized);
        if (contentType == null || contentType.isBlank()) {
            contentType = fallbackType(normalized);
        }
        long durationMillis = Math.max(0L,
                (System.nanoTime() - startedAt) / 1_000_000L);
        return new FileAnalysis(normalized, attributesAfter.size(),
                attributesAfter.lastModifiedTime().toInstant(), contentType,
                encoding.name(), encoding.detail(), Files.isReadable(normalized),
                Files.isWritable(normalized), lock.state(), lock.detail(),
                HexFormat.of().formatHex(sha256.digest()),
                HexFormat.of().formatHex(sha1.digest()),
                HexFormat.of().formatHex(md5.digest()), durationMillis);
    }

    static EncodingGuess detectEncoding(byte[] sample) {
        return detectEncoding(sample, false);
    }

    private static EncodingGuess detectEncoding(byte[] sample, boolean sampleTruncated) {
        if (sample.length == 0) {
            return new EncodingGuess("空文件", "没有可用于判断编码的内容");
        }
        if (startsWith(sample, 0xEF, 0xBB, 0xBF)) {
            return new EncodingGuess("UTF-8（BOM）", "检测到 UTF-8 字节顺序标记");
        }
        if (startsWith(sample, 0x00, 0x00, 0xFE, 0xFF)) {
            return new EncodingGuess("UTF-32 BE", "检测到 UTF-32 BE 字节顺序标记");
        }
        if (startsWith(sample, 0xFF, 0xFE, 0x00, 0x00)) {
            return new EncodingGuess("UTF-32 LE", "检测到 UTF-32 LE 字节顺序标记");
        }
        if (startsWith(sample, 0xFE, 0xFF)) {
            return new EncodingGuess("UTF-16 BE", "检测到 UTF-16 BE 字节顺序标记");
        }
        if (startsWith(sample, 0xFF, 0xFE)) {
            return new EncodingGuess("UTF-16 LE", "检测到 UTF-16 LE 字节顺序标记");
        }

        int zeroEven = 0;
        int zeroOdd = 0;
        int controls = 0;
        boolean ascii = true;
        for (int i = 0; i < sample.length; i++) {
            int value = sample[i] & 0xFF;
            if (value == 0) {
                if ((i & 1) == 0) {
                    zeroEven++;
                } else {
                    zeroOdd++;
                }
            }
            if (value > 0x7F) {
                ascii = false;
            }
            if (value < 0x20 && value != '\n' && value != '\r' && value != '\t'
                    && value != 0) {
                controls++;
            }
        }
        int pairs = Math.max(1, sample.length / 2);
        if (zeroOdd > pairs * 0.35 && zeroEven < pairs * 0.05
                && isLikelyTextLane(sample, 0)) {
            return new EncodingGuess("UTF-16 LE（推测）", "未检测到 BOM，依据空字节分布判断");
        }
        if (zeroEven > pairs * 0.35 && zeroOdd < pairs * 0.05
                && isLikelyTextLane(sample, 1)) {
            return new EncodingGuess("UTF-16 BE（推测）", "未检测到 BOM，依据空字节分布判断");
        }
        if (zeroEven + zeroOdd > sample.length * 0.01
                || controls > sample.length * 0.08) {
            return new EncodingGuess("二进制", "空字节或控制字节比例较高");
        }
        if (ascii) {
            return new EncodingGuess("ASCII / UTF-8", "内容仅包含 ASCII 字节");
        }
        if (isValid(sample, StandardCharsets.UTF_8)
                || sampleTruncated && isValidUtf8WithIncompleteTail(sample)) {
            return new EncodingGuess("UTF-8", "通过严格 UTF-8 解码校验");
        }
        try {
            if (isValid(sample, java.nio.charset.Charset.forName("GB18030"))) {
                return new EncodingGuess("GB18030 / GBK（推测）",
                        "未通过 UTF-8 校验，可按中文 Windows 编码进一步确认");
            }
        } catch (RuntimeException ignored) {
            // GB18030 is required by standard JDKs; keep an unknown fallback for custom runtimes.
        }
        return new EncodingGuess("未知文本编码", "未匹配常见编码特征");
    }

    private static LockInspection inspectLock(Path file) {
        if (!Files.isWritable(file)) {
            return new LockInspection(LockState.READ_ONLY,
                    "当前用户无写权限，无法执行独占锁探测");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    return new LockInspection(LockState.LOCKED,
                            "未能取得临时独占锁，文件可能正在被其他程序占用");
                }
                return new LockInspection(LockState.AVAILABLE,
                        "已取得并立即释放临时独占锁，未发现可检测的独占占用");
            }
        } catch (OverlappingFileLockException e) {
            return new LockInspection(LockState.LOCKED, "文件已在当前 Java 进程中加锁");
        } catch (AccessDeniedException e) {
            return new LockInspection(LockState.LOCKED,
                    "Windows 拒绝写入式打开，文件可能被独占占用或权限受限");
        } catch (IOException | SecurityException e) {
            return new LockInspection(LockState.UNKNOWN,
                    "无法完成占用探测：" + errorMessage(e));
        }
    }

    private static Path validate(Path file) throws IOException {
        if (file == null) {
            throw new IOException("请选择要分析的文件");
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("目标不是普通文件：" + normalized);
        }
        if (!Files.isReadable(normalized)) {
            throw new IOException("当前用户无法读取该文件");
        }
        return normalized;
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行时不支持 " + algorithm, e);
        }
    }

    private static boolean isValid(byte[] sample, java.nio.charset.Charset charset) {
        try {
            charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sample));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean isValidUtf8WithIncompleteTail(byte[] sample) {
        for (int tailLength = 1; tailLength <= Math.min(3, sample.length); tailLength++) {
            int start = sample.length - tailLength;
            int lead = sample[start] & 0xFF;
            int expectedLength = lead >= 0xC2 && lead <= 0xDF ? 2
                    : lead >= 0xE0 && lead <= 0xEF ? 3
                    : lead >= 0xF0 && lead <= 0xF4 ? 4 : -1;
            if (expectedLength <= tailLength) {
                continue;
            }
            boolean continuationsValid = true;
            for (int index = start + 1; index < sample.length; index++) {
                int value = sample[index] & 0xFF;
                if (value < 0x80 || value > 0xBF) {
                    continuationsValid = false;
                    break;
                }
            }
            if (continuationsValid && isValid(
                    java.util.Arrays.copyOf(sample, start), StandardCharsets.UTF_8)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fileIdentityChanged(BasicFileAttributes before,
                                               BasicFileAttributes after) {
        return before.fileKey() != null && after.fileKey() != null
                && !before.fileKey().equals(after.fileKey());
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLikelyTextLane(byte[] sample, int lane) {
        int values = 0;
        int printable = 0;
        for (int i = lane; i < sample.length; i += 2) {
            int value = sample[i] & 0xFF;
            values++;
            if (value >= 0x20 || value == '\n' || value == '\r' || value == '\t') {
                printable++;
            }
        }
        return values > 0 && printable >= Math.ceil(values * 0.85);
    }

    private static String fallbackType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? "文件（." + name.substring(dot + 1).toLowerCase(Locale.ROOT) + "）"
                : "未知文件类型";
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    public enum LockState {
        AVAILABLE, LOCKED, READ_ONLY, UNKNOWN
    }

    public record FileAnalysis(Path path, long size, Instant modifiedAt, String contentType,
                               String encoding, String encodingDetail, boolean readable,
                               boolean writable, LockState lockState, String lockDetail,
                               String sha256, String sha1, String md5, long durationMillis) {
    }

    record EncodingGuess(String name, String detail) {
    }

    private record LockInspection(LockState state, String detail) {
    }
}
