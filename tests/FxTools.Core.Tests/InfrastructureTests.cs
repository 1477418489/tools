using FxTools.Core.Infrastructure;

namespace FxTools.Core.Tests;

public sealed class InfrastructureTests : IDisposable
{
    private readonly string temporaryDirectory = Path.Combine(
        Path.GetTempPath(), $"FxTools.Tests.{Guid.NewGuid():N}");

    [Fact]
    public void DataFileRejectsDirectoryTraversal()
    {
        Assert.Throws<ArgumentException>(() => AppDataPaths.DataFile("../settings.json"));
        Assert.Throws<ArgumentException>(() => AppDataPaths.DataFile("folder/settings.json"));
    }

    [Fact]
    public async Task JsonStoreReadsCaseInsensitiveAndWritesCamelCaseAtomically()
    {
        Directory.CreateDirectory(temporaryDirectory);
        string path = Path.Combine(temporaryDirectory, "settings.json");
        await File.WriteAllTextAsync(path, "{\"DISPLAYNAME\":\"old\",\"COUNT\":2}");
        using AtomicJsonStore store = new(path);

        SampleSettings loaded = await store.LoadAsync(() => new SampleSettings());
        Assert.Equal("old", loaded.DisplayName);
        Assert.Equal(2, loaded.Count);

        await store.SaveAsync(new SampleSettings { DisplayName = "new", Count = 3 });
        string json = await File.ReadAllTextAsync(path);
        Assert.Contains("\"displayName\"", json, StringComparison.Ordinal);
        Assert.DoesNotContain(Directory.EnumerateFiles(temporaryDirectory),
            candidate => candidate.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void BoundedBufferEvictsOldEntriesAndCapsSingleEntry()
    {
        BoundedTextBuffer buffer = new(2, 12, 6);
        buffer.Add("first");
        buffer.Add("second");
        buffer.Add("123456789");

        Assert.InRange(buffer.Count, 1, 2);
        Assert.InRange(buffer.CharacterCount, 0, 12);
        Assert.DoesNotContain("first", buffer.Snapshot());
    }

    public void Dispose()
    {
        if (Directory.Exists(temporaryDirectory))
        {
            Directory.Delete(temporaryDirectory, recursive: true);
        }
        GC.SuppressFinalize(this);
    }

    private sealed class SampleSettings
    {
        public string DisplayName { get; set; } = string.Empty;
        public int Count { get; set; }
    }
}
