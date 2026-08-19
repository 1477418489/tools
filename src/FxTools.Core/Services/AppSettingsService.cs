using FxTools.Core.Infrastructure;
using FxTools.Core.Windows;

namespace FxTools.Core.Services;

public sealed class AppSettings
{
    public bool CloseToTray { get; set; } = true;
    public bool ReminderSoundEnabled { get; set; } = true;
    public bool StartWithWindows { get; set; }

    public AppSettings Copy() => new()
    {
        CloseToTray = CloseToTray,
        ReminderSoundEnabled = ReminderSoundEnabled,
        StartWithWindows = StartWithWindows
    };
}

public interface IStartupRegistration
{
    bool IsEnabled();
    void SetEnabled(bool enabled);
}

public sealed class AppSettingsService : IDisposable
{
    private readonly AtomicJsonStore store;
    private readonly IStartupRegistration startupRegistration;
    private readonly SemaphoreSlim gate = new(1, 1);
    private bool initialized;
    private bool disposed;

    public AppSettingsService(string? path = null, IStartupRegistration? startupRegistration = null)
    {
        store = new AtomicJsonStore(path ?? AppDataPaths.DataFile("settings.json"));
        this.startupRegistration = startupRegistration ?? new WindowsStartupService();
    }

    public AppSettings Current { get; private set; } = new();

    public event EventHandler? Changed;

    public async Task<string?> InitializeAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        AppSettings loaded = await store.LoadAsync(static () => new AppSettings(), cancellationToken)
            .ConfigureAwait(false);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (initialized)
            {
                return null;
            }

            Current = loaded.Copy();
            initialized = true;
        }
        finally
        {
            gate.Release();
        }

        try
        {
            startupRegistration.SetEnabled(Current.StartWithWindows);
            return null;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException
                                          or PlatformNotSupportedException)
        {
            return $"同步开机启动设置失败: {exception.Message}";
        }
    }

    public async Task UpdateAsync(AppSettings settings, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ObjectDisposedException.ThrowIf(disposed, this);
        AppSettings updated = settings.Copy();
        bool changed;

        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            EnsureInitialized();
            AppSettings previous = Current.Copy();
            changed = previous.CloseToTray != updated.CloseToTray
                || previous.ReminderSoundEnabled != updated.ReminderSoundEnabled
                || previous.StartWithWindows != updated.StartWithWindows;
            if (!changed)
            {
                return;
            }

            bool startupChanged = previous.StartWithWindows != updated.StartWithWindows;
            if (startupChanged)
            {
                startupRegistration.SetEnabled(updated.StartWithWindows);
            }

            try
            {
                await store.SaveAsync(updated, cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                if (startupChanged)
                {
                    try
                    {
                        startupRegistration.SetEnabled(previous.StartWithWindows);
                    }
                    catch (Exception rollbackException) when (rollbackException is IOException
                                                               or UnauthorizedAccessException
                                                               or PlatformNotSupportedException)
                    {
                    }
                }
                throw;
            }

            Current = updated;
        }
        finally
        {
            gate.Release();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    private void EnsureInitialized()
    {
        if (!initialized)
        {
            throw new InvalidOperationException("应用设置尚未初始化。");
        }
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;
        gate.Dispose();
        store.Dispose();
    }
}
