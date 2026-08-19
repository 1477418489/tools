using System.Buffers;
using System.Text;
using System.Text.RegularExpressions;
using System.Runtime.InteropServices;

namespace FxTools.Core.Services;

public sealed partial class LogFileTailer
{
    public const int MaxBytesPerPoll = 256 * 1024;
    public const int MaxLinesPerPoll = 200;
    public const int MaxLineBytes = 32 * 1024;
    private readonly string path;
    private readonly List<byte> pendingLine = new(MaxLineBytes);
    private long position;
    private DateTime creationTimeUtc;
    private bool observed;
    private bool lineTruncated;

    public LogFileTailer(string path) => this.path = Path.GetFullPath(path);

    public async Task<IReadOnlyList<string>> PollAsync(
        bool startAtEnd,
        CancellationToken cancellationToken = default)
    {
        FileInfo info = new(path);
        if (!info.Exists) { return []; }
        info.Refresh();
        if (!observed)
        {
            observed = true;
            creationTimeUtc = info.CreationTimeUtc;
            position = startAtEnd ? info.Length : 0;
            if (startAtEnd) { return []; }
        }
        else if (info.CreationTimeUtc != creationTimeUtc || info.Length < position)
        {
            position = 0;
            pendingLine.Clear();
            lineTruncated = false;
            creationTimeUtc = info.CreationTimeUtc;
        }

        List<string> lines = [];
        byte[] buffer = ArrayPool<byte>.Shared.Rent(16 * 1024);
        int consumed = 0;
        try
        {
            await using FileStream stream = new(path, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete, 16 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            stream.Position = Math.Min(position, stream.Length);
            while (consumed < MaxBytesPerPoll && lines.Count < MaxLinesPerPoll)
            {
                int wanted = Math.Min(buffer.Length, MaxBytesPerPoll - consumed);
                int read = await stream.ReadAsync(buffer.AsMemory(0, wanted), cancellationToken).ConfigureAwait(false);
                if (read == 0) { break; }
                for (int index = 0; index < read; index++)
                {
                    byte value = buffer[index];
                    position++;
                    consumed++;
                    if (value == (byte)'\n')
                    {
                        lines.Add(FinishLine());
                        if (lines.Count >= MaxLinesPerPoll) { break; }
                    }
                    else if (value != (byte)'\r')
                    {
                        if (pendingLine.Count < MaxLineBytes) { pendingLine.Add(value); }
                        else { lineTruncated = true; }
                    }
                }
            }
        }
        finally { ArrayPool<byte>.Shared.Return(buffer); }
        return lines;
    }

    private string FinishLine()
    {
        string line = Encoding.UTF8.GetString(CollectionsMarshal.AsSpan(pendingLine));
        pendingLine.Clear();
        if (lineTruncated) { line += "... [单行日志已截断]"; lineTruncated = false; }
        return AnsiEscape().Replace(line, string.Empty);
    }

    [GeneratedRegex("\\x1B(?:[@-_][0-?]*[ -/]*[@-~]|\\][^\\x07]*(?:\\x07|\\x1B\\\\))", RegexOptions.CultureInvariant)]
    private static partial Regex AnsiEscape();
}
