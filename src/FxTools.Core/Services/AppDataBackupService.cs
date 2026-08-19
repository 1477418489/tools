using System.IO.Compression;
using FxTools.Core.Infrastructure;

namespace FxTools.Core.Services;

public sealed class AppDataBackupService
{
    private const int BufferSize = 64 * 1024;

    public static Task<BackupResult> ExportAsync(
        string destination,
        CancellationToken cancellationToken = default) =>
        ExportAsync(AppDataPaths.DataDirectory, destination, cancellationToken);

    public static async Task<BackupResult> ExportAsync(
        string sourceDirectory,
        string destination,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourceDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(destination);
        string source = Path.GetFullPath(sourceDirectory);
        string target = Path.GetFullPath(destination);
        if (!Directory.Exists(source))
        {
            throw new DirectoryNotFoundException($"应用数据目录不存在: {source}");
        }
        if (!string.Equals(Path.GetExtension(target), ".zip", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("备份目标必须是 ZIP 文件。");
        }
        if (IsInsideDirectory(target, source))
        {
            throw new InvalidDataException("备份文件不能保存在应用数据目录内。");
        }

        string? parent = Path.GetDirectoryName(target);
        if (string.IsNullOrWhiteSpace(parent))
        {
            throw new InvalidDataException("备份目标缺少父目录。");
        }
        Directory.CreateDirectory(parent);
        source = ResolveExistingDirectory(source);
        string resolvedParent = ResolveExistingDirectory(parent);
        target = Path.Combine(resolvedParent, Path.GetFileName(target));
        if (IsInsideDirectory(target, source))
        {
            throw new InvalidDataException("备份文件不能通过目录链接保存在应用数据目录内。");
        }
        FileInfo destinationInfo = new(target);
        if (destinationInfo.Exists && (destinationInfo.Attributes & FileAttributes.ReparsePoint) != 0)
        {
            throw new InvalidDataException("备份目标不能是符号链接。");
        }

        string temporary = Path.Combine(parent, $".fxtools-backup-{Guid.NewGuid():N}.tmp");
        int fileCount = 0;
        long uncompressedBytes = 0;
        try
        {
            await using (FileStream output = new(
                temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, BufferSize,
                FileOptions.Asynchronous | FileOptions.SequentialScan))
            using (ZipArchive archive = new(output, ZipArchiveMode.Create, leaveOpen: false))
            {
                EnumerationOptions options = new()
                {
                    RecurseSubdirectories = true,
                    IgnoreInaccessible = false,
                    AttributesToSkip = FileAttributes.ReparsePoint
                };
                foreach (string file in Directory.EnumerateFiles(source, "*", options))
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    string fullFile = Path.GetFullPath(file);
                    if (!IsInsideDirectory(fullFile, source))
                    {
                        continue;
                    }

                    string entryName = Path.GetRelativePath(source, fullFile).Replace('\\', '/');
                    ZipArchiveEntry entry = archive.CreateEntry(entryName, CompressionLevel.Optimal);
                    await using Stream entryStream = entry.Open();
                    await using FileStream input = new(
                        fullFile, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete,
                        BufferSize, FileOptions.Asynchronous | FileOptions.SequentialScan);
                    await input.CopyToAsync(entryStream, BufferSize, cancellationToken).ConfigureAwait(false);
                    fileCount++;
                    uncompressedBytes += input.Length;
                }
            }

            File.Move(temporary, target, overwrite: true);
            return new BackupResult(target, fileCount, uncompressedBytes);
        }
        finally
        {
            try
            {
                File.Delete(temporary);
            }
            catch (IOException)
            {
            }
        }
    }

    private static bool IsInsideDirectory(string candidate, string directory)
    {
        string normalizedDirectory = Path.TrimEndingDirectorySeparator(Path.GetFullPath(directory))
            + Path.DirectorySeparatorChar;
        string normalizedCandidate = Path.GetFullPath(candidate);
        return normalizedCandidate.StartsWith(normalizedDirectory, StringComparison.OrdinalIgnoreCase);
    }

    private static string ResolveExistingDirectory(string path)
    {
        string fullPath = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path));
        string root = Path.GetPathRoot(fullPath)
            ?? throw new InvalidDataException($"目录缺少根路径: {fullPath}");
        string current = root;
        foreach (string component in fullPath[root.Length..].Split(
                     Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries))
        {
            current = Path.Combine(current, component);
            DirectoryInfo directory = new(current);
            if (!directory.Exists)
            {
                throw new DirectoryNotFoundException($"目录不存在: {current}");
            }
            if ((directory.Attributes & FileAttributes.ReparsePoint) != 0)
            {
                FileSystemInfo? resolved = directory.ResolveLinkTarget(returnFinalTarget: true);
                if (resolved is not DirectoryInfo resolvedDirectory || !resolvedDirectory.Exists)
                {
                    throw new InvalidDataException($"无法解析目录链接: {current}");
                }
                current = Path.GetFullPath(resolvedDirectory.FullName);
            }
        }
        return current;
    }
}

public sealed record BackupResult(string Archive, int FileCount, long UncompressedBytes);
