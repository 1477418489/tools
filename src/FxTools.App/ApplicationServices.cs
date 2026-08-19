using FxTools.Core.Infrastructure;
using FxTools.Core.Services;

namespace FxTools.App;

internal sealed class ApplicationServices : IAsyncDisposable
{
    public AppSettingsService Settings { get; } = new();
    public MemoReminderService Reminders { get; } = new();

    public async Task<IReadOnlyList<string>> InitializeAsync(
        CancellationToken cancellationToken = default)
    {
        AppDataPaths.EnsureDataDirectory();
        List<string> warnings = [];
        try
        {
            string? startupWarning = await Settings.InitializeAsync(cancellationToken);
            if (!string.IsNullOrWhiteSpace(startupWarning))
            {
                warnings.Add(startupWarning);
            }
        }
        catch (Exception exception) when (exception is IOException
                                          or UnauthorizedAccessException
                                          or System.Text.Json.JsonException)
        {
            warnings.Add($"加载应用设置失败: {exception.Message}");
        }

        try
        {
            await Reminders.InitializeAsync(cancellationToken);
        }
        catch (Exception exception) when (exception is IOException
                                          or UnauthorizedAccessException
                                          or InvalidDataException
                                          or System.Text.Json.JsonException)
        {
            warnings.Add($"加载备忘提醒失败: {exception.Message}");
        }

        return warnings;
    }

    public async ValueTask DisposeAsync()
    {
        await Reminders.DisposeAsync();
        Settings.Dispose();
    }
}
