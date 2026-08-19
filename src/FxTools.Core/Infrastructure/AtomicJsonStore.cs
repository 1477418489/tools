using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace FxTools.Core.Infrastructure;

public sealed class AtomicJsonStore(string path) : IDisposable
{
    private static readonly JsonSerializerOptions ReadOptions = CreateReadOptions();
    private static readonly JsonSerializerOptions WriteOptions = CreateWriteOptions();

    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly string path = Path.GetFullPath(path ?? throw new ArgumentNullException(nameof(path)));

    private static JsonSerializerOptions CreateReadOptions()
    {
        JsonSerializerOptions options = new()
        {
            PropertyNameCaseInsensitive = true,
            AllowTrailingCommas = true,
            ReadCommentHandling = JsonCommentHandling.Skip
        };
        options.Converters.Add(new JsonStringEnumConverter());
        return options;
    }

    private static JsonSerializerOptions CreateWriteOptions()
    {
        JsonSerializerOptions options = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            WriteIndented = true
        };
        options.Converters.Add(new JsonStringEnumConverter());
        return options;
    }

    public async Task<T> LoadAsync<T>(Func<T> defaultFactory, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(defaultFactory);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!File.Exists(path))
            {
                return defaultFactory();
            }

            await using FileStream stream = new(
                path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            T? value = await JsonSerializer.DeserializeAsync<T>(
                stream, ReadOptions, cancellationToken).ConfigureAwait(false);
            return value is null ? defaultFactory() : value;
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task SaveAsync<T>(T value, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(value);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            byte[] json = JsonSerializer.SerializeToUtf8Bytes(value, WriteOptions);
            await AtomicFileWriter.WriteBytesAsync(path, json, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            gate.Release();
        }
    }

    public void Dispose() => gate.Dispose();
}

public static class AtomicFileWriter
{
    public static Task WriteUtf8Async(
        string path,
        string content,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(content);
        return WriteBytesAsync(path, Encoding.UTF8.GetBytes(content), cancellationToken);
    }

    public static async Task WriteBytesAsync(
        string path,
        ReadOnlyMemory<byte> content,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        string target = Path.GetFullPath(path);
        string? directory = Path.GetDirectoryName(target);
        if (directory is null)
        {
            throw new IOException($"目标文件缺少父目录: {target}");
        }

        Directory.CreateDirectory(directory);
        string temporary = Path.Combine(
            directory,
            $".{Path.GetFileName(target)}.{Guid.NewGuid():N}.tmp");
        try
        {
            await using (FileStream stream = new(
                temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 64 * 1024,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await stream.WriteAsync(content, cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }

            File.Move(temporary, target, overwrite: true);
        }
        finally
        {
            try
            {
                File.Delete(temporary);
            }
            catch (IOException)
            {
                // A successful move already removed the temporary file.
            }
        }
    }
}
