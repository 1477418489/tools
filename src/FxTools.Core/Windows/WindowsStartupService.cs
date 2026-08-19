using Microsoft.Win32;
using FxTools.Core.Services;

namespace FxTools.Core.Windows;

public sealed class WindowsStartupService : IStartupRegistration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "FxTools";

    public bool IsEnabled()
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("当前系统不支持 Windows 开机启动项。");
        }
        using RegistryKey? key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
        return key?.GetValue(ValueName) is string value && !string.IsNullOrWhiteSpace(value);
    }

    public void SetEnabled(bool enabled)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("当前系统不支持 Windows 开机启动项。");
        }
        if (enabled)
        {
            string executable = Environment.ProcessPath
                ?? throw new IOException("无法确定当前程序启动路径。");
            using RegistryKey key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true)
                ?? throw new IOException("无法打开当前用户的开机启动注册表项。");
            key.SetValue(ValueName, QuoteArgument(Path.GetFullPath(executable)), RegistryValueKind.String);
            return;
        }

        using RegistryKey? existing = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
        existing?.DeleteValue(ValueName, throwOnMissingValue: false);
    }

    internal static string QuoteArgument(string? value)
    {
        string input = value ?? string.Empty;
        if (input.Length > 0 && input.All(character => !char.IsWhiteSpace(character) && character != '"'))
        {
            return input;
        }

        System.Text.StringBuilder result = new(input.Length + 2);
        result.Append('"');
        int backslashes = 0;
        foreach (char character in input)
        {
            if (character == '\\')
            {
                backslashes++;
            }
            else if (character == '"')
            {
                result.Append('\\', checked(backslashes * 2 + 1));
                result.Append('"');
                backslashes = 0;
            }
            else
            {
                result.Append('\\', backslashes);
                result.Append(character);
                backslashes = 0;
            }
        }

        result.Append('\\', checked(backslashes * 2));
        result.Append('"');
        return result.ToString();
    }
}
