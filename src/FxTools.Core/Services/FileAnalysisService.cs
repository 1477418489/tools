using System.Buffers;
using System.Security.Cryptography;
using System.Text;

namespace FxTools.Core.Services;

public static class FileAnalysisService
{
    private const int BufferSize = 128 * 1024;
    private const int SampleLimit = 1024 * 1024;

    static FileAnalysisService() => Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);

    public static async Task<FileAnalysis> AnalyzeAsync(
        string path,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        string normalized = Path.GetFullPath(path);
        FileInfo before = new(normalized);
        if (!before.Exists)
        {
            throw new FileNotFoundException("请选择存在的普通文件。", normalized);
        }

        long lengthBefore = before.Length;
        DateTime modifiedBefore = before.LastWriteTimeUtc;
        long started = Environment.TickCount64;
        using IncrementalHash sha256 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        using IncrementalHash sha1 = IncrementalHash.CreateHash(HashAlgorithmName.SHA1);
        using IncrementalHash md5 = IncrementalHash.CreateHash(HashAlgorithmName.MD5);
        using MemoryStream sample = new(Math.Min((int)Math.Min(lengthBefore, SampleLimit), SampleLimit));
        byte[] buffer = ArrayPool<byte>.Shared.Rent(BufferSize);
        try
        {
            await using FileStream input = new(
                normalized, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete,
                BufferSize, FileOptions.Asynchronous | FileOptions.SequentialScan);
            while (true)
            {
                int read = await input.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
                if (read == 0)
                {
                    break;
                }
                sha256.AppendData(buffer, 0, read);
                sha1.AppendData(buffer, 0, read);
                md5.AppendData(buffer, 0, read);
                int remaining = SampleLimit - (int)sample.Length;
                if (remaining > 0)
                {
                    sample.Write(buffer, 0, Math.Min(read, remaining));
                }
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }

        cancellationToken.ThrowIfCancellationRequested();
        before.Refresh();
        if (!before.Exists || before.Length != lengthBefore || before.LastWriteTimeUtc != modifiedBefore)
        {
            throw new IOException("文件在分析过程中发生变化，请重新分析。");
        }

        EncodingGuess encoding = DetectEncoding(sample.GetBuffer().AsSpan(0, (int)sample.Length),
            lengthBefore > sample.Length);
        LockInspection lockInspection = InspectLock(normalized, before.IsReadOnly);
        return new FileAnalysis(
            normalized,
            lengthBefore,
            modifiedBefore,
            DetectContentType(normalized),
            encoding.Name,
            encoding.Detail,
            CanOpen(normalized, FileAccess.Read),
            !before.IsReadOnly && CanOpen(normalized, FileAccess.Write),
            lockInspection.State,
            lockInspection.Detail,
            Convert.ToHexString(sha256.GetHashAndReset()).ToLowerInvariant(),
            Convert.ToHexString(sha1.GetHashAndReset()).ToLowerInvariant(),
            Convert.ToHexString(md5.GetHashAndReset()).ToLowerInvariant(),
            Math.Max(0, Environment.TickCount64 - started));
    }

    public static EncodingGuess DetectEncoding(ReadOnlySpan<byte> sample, bool sampleTruncated = false)
    {
        if (sample.IsEmpty)
        {
            return new("空文件", "没有可用于判断编码的内容");
        }
        if (sample.StartsWith(new byte[] { 0xEF, 0xBB, 0xBF }))
        {
            return new("UTF-8（BOM）", "检测到 UTF-8 字节顺序标记");
        }
        if (sample.StartsWith(new byte[] { 0x00, 0x00, 0xFE, 0xFF }))
        {
            return new("UTF-32 BE", "检测到 UTF-32 BE 字节顺序标记");
        }
        if (sample.StartsWith(new byte[] { 0xFF, 0xFE, 0x00, 0x00 }))
        {
            return new("UTF-32 LE", "检测到 UTF-32 LE 字节顺序标记");
        }
        if (sample.StartsWith(new byte[] { 0xFE, 0xFF }))
        {
            return new("UTF-16 BE", "检测到 UTF-16 BE 字节顺序标记");
        }
        if (sample.StartsWith(new byte[] { 0xFF, 0xFE }))
        {
            return new("UTF-16 LE", "检测到 UTF-16 LE 字节顺序标记");
        }

        int zeroEven = 0;
        int zeroOdd = 0;
        int controls = 0;
        bool ascii = true;
        for (int index = 0; index < sample.Length; index++)
        {
            byte value = sample[index];
            if (value == 0)
            {
                if ((index & 1) == 0) { zeroEven++; } else { zeroOdd++; }
            }
            if (value > 0x7F) { ascii = false; }
            if (value < 0x20 && value is not (byte)'\n' and not (byte)'\r' and not (byte)'\t' and not 0)
            {
                controls++;
            }
        }

        int pairs = Math.Max(1, sample.Length / 2);
        if (zeroOdd > pairs * 0.35 && zeroEven < pairs * 0.05 && IsLikelyTextLane(sample, 0))
        {
            return new("UTF-16 LE（推测）", "未检测到 BOM，依据空字节分布判断");
        }
        if (zeroEven > pairs * 0.35 && zeroOdd < pairs * 0.05 && IsLikelyTextLane(sample, 1))
        {
            return new("UTF-16 BE（推测）", "未检测到 BOM，依据空字节分布判断");
        }
        if (zeroEven + zeroOdd > sample.Length * 0.01 || controls > sample.Length * 0.08)
        {
            return new("二进制", "空字节或控制字节比例较高");
        }
        if (ascii)
        {
            return new("ASCII / UTF-8", "内容仅包含 ASCII 字节");
        }
        if (IsValid(sample, new UTF8Encoding(false, true))
            || sampleTruncated && IsValidUtf8WithIncompleteTail(sample))
        {
            return new("UTF-8", "通过严格 UTF-8 解码校验");
        }
        Encoding gb18030 = Encoding.GetEncoding(
            "GB18030", EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
        return IsValid(sample, gb18030)
            ? new("GB18030 / GBK（推测）", "未通过 UTF-8 校验，可按中文 Windows 编码进一步确认")
            : new("未知文本编码", "未匹配常见编码特征");
    }

    private static bool IsValid(ReadOnlySpan<byte> sample, Encoding encoding)
    {
        try
        {
            _ = encoding.GetCharCount(sample);
            return true;
        }
        catch (DecoderFallbackException)
        {
            return false;
        }
    }

    private static bool IsValidUtf8WithIncompleteTail(ReadOnlySpan<byte> sample)
    {
        for (int tailLength = 1; tailLength <= Math.Min(3, sample.Length); tailLength++)
        {
            int start = sample.Length - tailLength;
            byte lead = sample[start];
            int expected = lead switch
            {
                >= 0xC2 and <= 0xDF => 2,
                >= 0xE0 and <= 0xEF => 3,
                >= 0xF0 and <= 0xF4 => 4,
                _ => -1
            };
            if (expected <= tailLength)
            {
                continue;
            }
            bool continuations = true;
            for (int index = start + 1; index < sample.Length; index++)
            {
                if (sample[index] is < 0x80 or > 0xBF)
                {
                    continuations = false;
                    break;
                }
            }
            if (continuations && IsValid(sample[..start], new UTF8Encoding(false, true)))
            {
                return true;
            }
        }
        return false;
    }

    private static bool IsLikelyTextLane(ReadOnlySpan<byte> sample, int lane)
    {
        int values = 0;
        int printable = 0;
        for (int index = lane; index < sample.Length; index += 2)
        {
            values++;
            byte value = sample[index];
            if (value >= 0x20 || value is (byte)'\n' or (byte)'\r' or (byte)'\t')
            {
                printable++;
            }
        }
        return values > 0 && printable >= Math.Ceiling(values * 0.85);
    }

    private static LockInspection InspectLock(string path, bool readOnly)
    {
        if (readOnly)
        {
            return new(LockState.ReadOnly, "文件具有只读属性，未执行写入式独占探测");
        }
        try
        {
            using FileStream _ = new(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
            return new(LockState.Available, "已取得并立即释放临时独占句柄，未发现独占占用");
        }
        catch (UnauthorizedAccessException)
        {
            return new(LockState.ReadOnly, "当前用户无写权限，或 Windows 拒绝写入式打开");
        }
        catch (IOException exception)
        {
            return new(LockState.Locked, $"未能取得独占句柄: {exception.Message}");
        }
    }

    private static bool CanOpen(string path, FileAccess access)
    {
        try
        {
            using FileStream _ = new(path, FileMode.Open, access, FileShare.ReadWrite | FileShare.Delete);
            return true;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static string DetectContentType(string path)
    {
        string extension = Path.GetExtension(path).ToLowerInvariant();
        return extension switch
        {
            ".txt" or ".log" or ".md" => "文本文件",
            ".json" => "JSON 数据",
            ".xml" => "XML 数据",
            ".csv" => "CSV 数据",
            ".zip" => "ZIP 压缩文件",
            ".jar" => "Java JAR 文件",
            ".exe" => "Windows 可执行文件",
            ".dll" => "动态链接库",
            ".png" => "PNG 图像",
            ".jpg" or ".jpeg" => "JPEG 图像",
            ".gif" => "GIF 图像",
            ".pdf" => "PDF 文档",
            "" => "未知文件类型",
            _ => $"文件（{extension}）"
        };
    }

    private readonly record struct LockInspection(LockState State, string Detail);
}

public sealed record FileAnalysis(
    string Path,
    long Size,
    DateTime ModifiedAtUtc,
    string ContentType,
    string Encoding,
    string EncodingDetail,
    bool Readable,
    bool Writable,
    LockState LockState,
    string LockDetail,
    string Sha256,
    string Sha1,
    string Md5,
    long DurationMilliseconds);

public readonly record struct EncodingGuess(string Name, string Detail);

public enum LockState
{
    Available,
    Locked,
    ReadOnly,
    Unknown
}
