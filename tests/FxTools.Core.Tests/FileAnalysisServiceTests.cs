using System.Security.Cryptography;
using System.Text;
using FxTools.Core.Services;

namespace FxTools.Core.Tests;

public sealed class FileAnalysisServiceTests : IDisposable
{
    private readonly string path = Path.Combine(
        Path.GetTempPath(), $"FxTools.FileAnalysis.{Guid.NewGuid():N}.txt");

    [Fact]
    public async Task AnalyzeStreamsHashesAndDetectsUtf8()
    {
        const string content = "FxTools 文件分析";
        await File.WriteAllTextAsync(path, content, new UTF8Encoding(false));

        FileAnalysis result = await FileAnalysisService.AnalyzeAsync(path);

        Assert.Equal(Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(content))).ToLowerInvariant(),
            result.Sha256);
        Assert.Equal("UTF-8", result.Encoding);
        Assert.Equal(content.Length == 0 ? 0 : Encoding.UTF8.GetByteCount(content), result.Size);
    }

    [Fact]
    public void DetectEncodingRecognizesUtf16LittleEndianWithoutBom()
    {
        byte[] bytes = Encoding.Unicode.GetBytes("plain text");
        EncodingGuess result = FileAnalysisService.DetectEncoding(bytes);
        Assert.Contains("UTF-16 LE", result.Name, StringComparison.Ordinal);
    }

    public void Dispose()
    {
        File.Delete(path);
        GC.SuppressFinalize(this);
    }
}
