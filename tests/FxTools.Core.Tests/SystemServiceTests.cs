using FxTools.Core.Services;

namespace FxTools.Core.Tests;

public sealed class SystemServiceTests
{
    [Fact]
    public void FirstMeaningfulLineSkipsEmptyLines()
    {
        Assert.Equal("git version 2", DevEnvironmentService.FirstMeaningfulLine("\r\n  \r\ngit version 2\r\n"));
    }

    [Fact]
    public async Task ProcessPortSnapshotContainsCurrentProcess()
    {
        ProcessPortSnapshot snapshot = await ProcessPortService.CaptureAsync();
        Assert.Contains(snapshot.Processes, process => process.ProcessId == Environment.ProcessId);
    }
}
