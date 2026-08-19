namespace FxTools.Core.Infrastructure;

public static class AppDataPaths
{
    private const string AppDirectoryName = "FxTools";

    public static string DataDirectory { get; } = ResolveDataDirectory(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile));

    public static string DataFile(string fileName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(fileName);
        if (Path.IsPathFullyQualified(fileName)
            || fileName is "." or ".."
            || !string.Equals(Path.GetFileName(fileName), fileName, StringComparison.Ordinal)
            || fileName.IndexOfAny([Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar]) >= 0)
        {
            throw new ArgumentException("数据文件名不能包含目录。", nameof(fileName));
        }

        return Path.Combine(DataDirectory, fileName);
    }

    public static void EnsureDataDirectory() => Directory.CreateDirectory(DataDirectory);

    public static string ResolveDataDirectory(string? localAppData, string? userProfile)
    {
        string? root = string.IsNullOrWhiteSpace(localAppData)
            ? null
            : localAppData;
        if (root is null && !string.IsNullOrWhiteSpace(userProfile))
        {
            root = Path.Combine(userProfile, "AppData", "Local");
        }

        if (string.IsNullOrWhiteSpace(root))
        {
            throw new InvalidOperationException("无法确定用户数据目录。");
        }

        return Path.GetFullPath(Path.Combine(root, AppDirectoryName));
    }
}
