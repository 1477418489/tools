package plugin.javafxtools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Reads Windows, firmware and wake capabilities without changing system state.
 */
public final class WindowsPowerDiagnosticsService {
    private static final int COMMAND_TIMEOUT_SECONDS = 25;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean supported;
    private final CommandRunner commandRunner;

    public WindowsPowerDiagnosticsService() {
        this(System.getProperty("os.name", ""), new ProcessCommandRunner());
    }

    WindowsPowerDiagnosticsService(String osName, CommandRunner commandRunner) {
        supported = osName != null
                && osName.toLowerCase(Locale.ROOT).startsWith("windows");
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    public boolean isSupported() {
        return supported;
    }

    public PowerDiagnostics detect() throws IOException {
        if (!supported) {
            throw new IOException("电源能力检测仅支持 Windows 系统");
        }

        CommandResult result = commandRunner.run(List.of(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encodePowerShell(diagnosticsScript())));
        if (result.exitCode() != 0) {
            String details = cleanOutput(result.output());
            throw new IOException(details.isBlank()
                    ? "读取 Windows 电源能力失败，退出码: " + result.exitCode()
                    : "读取 Windows 电源能力失败: " + details);
        }
        return parseDiagnostics(result.output());
    }

    PowerDiagnostics parseDiagnostics(String output) throws IOException {
        String json = cleanOutput(output).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("{") && line.endsWith("}"))
                .reduce((previous, current) -> current)
                .orElseThrow(() -> new IOException("电源检测未返回有效 JSON"));

        JsonNode root = JSON.readTree(json);
        String powerStatesText = text(root, "powerStatesText");
        boolean hibernationConfigured = root.path("hibernationConfigured").asBoolean(false);
        PowerStateCapabilities powerStates = parsePowerStates(
                powerStatesText, hibernationConfigured);
        WakeTimerStatus wakeTimerStatus = parseWakeTimerStatus(
                root.path("wakeTimerExitCode").asInt(-1),
                text(root, "wakeTimerText"));

        return new PowerDiagnostics(
                text(root, "systemManufacturer"),
                text(root, "systemModel"),
                text(root, "boardManufacturer"),
                text(root, "boardProduct"),
                text(root, "biosManufacturer"),
                text(root, "biosVersion"),
                text(root, "biosReleaseDate"),
                FirmwareType.fromCode(root.path("firmwareType").asInt(0)),
                powerStates,
                stringList(root.path("wakeDevices")),
                wakeTimerStatus,
                concise(text(root, "wakeTimerText"), 220),
                stringList(root.path("biosManagementInterfaces")));
    }

    static PowerStateCapabilities parsePowerStates(String output,
                                                   boolean hibernationConfigured) {
        String value = output == null ? "" : output;
        String lower = value.toLowerCase(Locale.ROOT);
        int unavailableIndex = firstPositive(
                lower.indexOf("此系统上没有以下睡眠状态"),
                lower.indexOf("以下睡眠状态不可用"),
                lower.indexOf("the following sleep states are not available"));
        String available = unavailableIndex >= 0
                ? lower.substring(0, unavailableIndex) : lower;

        boolean s3Supported = available.contains("(s3)");
        boolean modernStandbySupported = available.contains("(s0")
                || available.contains("modern standby");
        boolean hibernationAvailable = available.contains("休眠")
                || available.contains("hibernate");
        boolean fastStartupAvailable = available.contains("快速启动")
                || available.contains("fast startup");
        return new PowerStateCapabilities(
                s3Supported,
                modernStandbySupported,
                hibernationConfigured,
                hibernationAvailable,
                fastStartupAvailable,
                concise(value, 500));
    }

    static WakeTimerStatus parseWakeTimerStatus(int exitCode, String output) {
        String value = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (exitCode == 0) {
            if (value.contains("没有活动的唤醒计时器")
                    || value.contains("no active wake timers")) {
                return WakeTimerStatus.NONE;
            }
            return value.isBlank() ? WakeTimerStatus.NONE : WakeTimerStatus.ACTIVE;
        }
        if (value.contains("管理员") || value.contains("administrator")
                || value.contains("elevat")) {
            return WakeTimerStatus.ADMIN_REQUIRED;
        }
        return WakeTimerStatus.UNAVAILABLE;
    }

    private String diagnosticsScript() {
        return """
                $ErrorActionPreference='Stop';
                [Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);
                $system=Get-CimInstance Win32_ComputerSystem;
                $bios=Get-CimInstance Win32_BIOS;
                $board=Get-CimInstance Win32_BaseBoard | Select-Object -First 1;
                [uint32]$firmwareType=0;
                try {
                    Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class FxToolsFirmwareProbe { [DllImport("kernel32.dll")] public static extern bool GetFirmwareType(out uint firmwareType); }' -ErrorAction Stop;
                    if(-not [FxToolsFirmwareProbe]::GetFirmwareType([ref]$firmwareType)){$firmwareType=0}
                } catch {
                    $firmwareType=(Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control' -Name PEFirmwareType -ErrorAction SilentlyContinue).PEFirmwareType;
                    if($null -eq $firmwareType){$firmwareType=0}
                }
                function Test-FxToolsCimClass([string]$namespace,[string]$className) {
                    try { return $null -ne (Get-CimClass -Namespace $namespace -ClassName $className -ErrorAction Stop) }
                    catch { return $false }
                }
                $interfaces=[System.Collections.Generic.List[string]]::new();
                if(Test-FxToolsCimClass 'root\\wmi' 'Lenovo_SetBiosSetting'){$interfaces.Add('Lenovo WMI')}
                if(Test-FxToolsCimClass 'root\\HP\\InstrumentedBIOS' 'HP_BIOSSettingInterface'){$interfaces.Add('HP BIOS WMI')}
                if(Test-FxToolsCimClass 'root\\dcim\\sysman' 'DCIM_BIOSService'){$interfaces.Add('Dell DCIM')}
                $powerStatesLines=@(& powercfg.exe /a 2>&1);
                $powerStatesText=($powerStatesLines -join [Environment]::NewLine).Trim();
                $hibernateValue=(Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Power' -Name HibernateEnabled -ErrorAction SilentlyContinue).HibernateEnabled;
                $wakeDevices=@(& powercfg.exe /devicequery wake_armed 2>$null) | ForEach-Object {$_.ToString().Trim()} | Where-Object {$_ -and $_ -ne 'NONE'};
                $ErrorActionPreference='Continue';
                $wakeTimerLines=@(& powercfg.exe /waketimers 2>&1);
                $wakeTimerExitCode=$LASTEXITCODE;
                $ErrorActionPreference='Stop';
                $wakeTimerText=($wakeTimerLines -join [Environment]::NewLine).Trim();
                [pscustomobject]@{
                    systemManufacturer=[string]$system.Manufacturer;
                    systemModel=[string]$system.Model;
                    boardManufacturer=[string]$board.Manufacturer;
                    boardProduct=[string]$board.Product;
                    biosManufacturer=[string]$bios.Manufacturer;
                    biosVersion=[string]$bios.SMBIOSBIOSVersion;
                    biosReleaseDate=if($bios.ReleaseDate){$bios.ReleaseDate.ToString('yyyy-MM-dd')}else{''};
                    firmwareType=[int]$firmwareType;
                    hibernationConfigured=([int]$hibernateValue -eq 1);
                    powerStatesText=$powerStatesText;
                    wakeDevices=@($wakeDevices);
                    wakeTimerExitCode=[int]$wakeTimerExitCode;
                    wakeTimerText=$wakeTimerText;
                    biosManagementInterfaces=@($interfaces)
                } | ConvertTo-Json -Compress -Depth 4;
                """;
    }

    private static int firstPositive(int... values) {
        int result = -1;
        for (int value : values) {
            if (value >= 0 && (result < 0 || value < result)) {
                result = value;
            }
        }
        return result;
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").strip();
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").strip();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private static String concise(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximumLength
                ? normalized : normalized.substring(0, maximumLength - 3) + "...";
    }

    private String cleanOutput(String output) {
        return output == null ? "" : output.replace("\uFEFF", "").strip();
    }

    private String encodePowerShell(String script) {
        return Base64.getEncoder().encodeToString(
                script.getBytes(StandardCharsets.UTF_16LE));
    }

    public enum FirmwareType {
        BIOS("传统 BIOS"),
        UEFI("UEFI"),
        UNKNOWN("未知");

        private final String displayName;

        FirmwareType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        static FirmwareType fromCode(int code) {
            return switch (code) {
                case 1 -> BIOS;
                case 2 -> UEFI;
                default -> UNKNOWN;
            };
        }
    }

    public enum WakeTimerStatus {
        ACTIVE,
        NONE,
        ADMIN_REQUIRED,
        UNAVAILABLE
    }

    public record PowerStateCapabilities(boolean s3Supported,
                                         boolean modernStandbySupported,
                                         boolean hibernationConfigured,
                                         boolean hibernationAvailable,
                                         boolean fastStartupAvailable,
                                         String summary) {
    }

    public record PowerDiagnostics(String systemManufacturer,
                                   String systemModel,
                                   String boardManufacturer,
                                   String boardProduct,
                                   String biosManufacturer,
                                   String biosVersion,
                                   String biosReleaseDate,
                                   FirmwareType firmwareType,
                                   PowerStateCapabilities powerStates,
                                   List<String> wakeArmedDevices,
                                   WakeTimerStatus wakeTimerStatus,
                                   String wakeTimerDetails,
                                   List<String> biosManagementInterfaces) {
        public boolean vendorBiosInterfaceDetected() {
            return !biosManagementInterfaces.isEmpty();
        }
    }

    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(List<String> command) throws IOException;
    }

    record CommandResult(int exitCode, String output) {
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command) throws IOException {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try {
                if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Windows 电源能力检测超时");
                }
                String output = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                return new CommandResult(process.exitValue(), output);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Windows 电源能力检测被中断", e);
            }
        }
    }
}
