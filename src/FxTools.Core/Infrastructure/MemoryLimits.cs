namespace FxTools.Core.Infrastructure;

public static class MemoryLimits
{
    public const int DisplayLogLines = 800;
    public const int DisplayLogCharacters = 1_000_000;
    public const int PendingLogEntries = 500;
    public const int PendingLogCharacters = 500_000;
    public const int SingleLogCharacters = 100_000;
    public const int LogLineCharacters = 32 * 1024;
    public const int MatchQueueEntries = 500;
    public const int MatchQueueCharacters = 1_000_000;
    public const int RecentMatches = 200;
    public const int AutomationQueueEntries = 32;
    public const int HttpBodyCharacters = 200_000;
    public const int Base64InputCharacters = 1_000_000;
}
