package plugin.javafxtools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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

        CommandResult result = commandRunner.run(diagnosticsScript());
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
                text(root, "serialNumber"),
                text(root, "systemUuid"),
                text(root, "boardManufacturer"),
                text(root, "boardProduct"),
                text(root, "boardSerialNumber"),
                text(root, "processorName"),
                Math.max(0, root.path("processorCores").asInt(0)),
                Math.max(0, root.path("processorLogicalProcessors").asInt(0)),
                Math.max(0, root.path("processorMaxClockMhz").asInt(0)),
                Math.max(0, root.path("totalPhysicalMemoryBytes").asLong(0)),
                memoryModules(root.path("memoryModules")),
                graphicsAdapters(root.path("graphicsAdapters")),
                temperatureReadings(root.path("temperatures")),
                text(root, "operatingSystem"),
                text(root, "biosManufacturer"),
                text(root, "biosVersion"),
                text(root, "biosReleaseDate"),
                text(root, "activePowerPlan"),
                text(root, "powerSupplyStatus"),
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
                $script:fxToolsCimAvailable=$true;
                function Get-FxToolsCimInstances([string]$className,[string]$namespace='root\\cimv2') {
                    if(-not $script:fxToolsCimAvailable){return @()}
                    try { return @(Get-CimInstance -Namespace $namespace -ClassName $className -ErrorAction Stop) }
                    catch {
                        if($namespace -eq 'root\\cimv2'){$script:fxToolsCimAvailable=$false}
                        return @()
                    }
                }
                function Get-FxToolsRegistryValue([string]$path,[string]$name) {
                    try { return Get-ItemPropertyValue -LiteralPath $path -Name $name -ErrorAction Stop }
                    catch { return $null }
                }
                $system=@(Get-FxToolsCimInstances 'Win32_ComputerSystem') | Select-Object -First 1;
                $product=@(Get-FxToolsCimInstances 'Win32_ComputerSystemProduct') | Select-Object -First 1;
                $bios=@(Get-FxToolsCimInstances 'Win32_BIOS') | Select-Object -First 1;
                $board=@(Get-FxToolsCimInstances 'Win32_BaseBoard') | Select-Object -First 1;
                $processor=@(Get-FxToolsCimInstances 'Win32_Processor') | Select-Object -First 1;
                $os=@(Get-FxToolsCimInstances 'Win32_OperatingSystem') | Select-Object -First 1;
                $memoryModules=@(Get-FxToolsCimInstances 'Win32_PhysicalMemory' | ForEach-Object {
                    [pscustomobject]@{
                        manufacturer=[string]$_.Manufacturer;
                        partNumber=([string]$_.PartNumber).Trim();
                        capacityBytes=[long]$_.Capacity;
                        speedMhz=[int]$_.Speed;
                        configuredSpeedMhz=[int]$_.ConfiguredClockSpeed
                    }
                });
                $biosRegistryPath='HKLM:\\HARDWARE\\DESCRIPTION\\System\\BIOS';
                $windowsRegistryPath='HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion';
                $cpuRegistryPath='HKLM:\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0';
                $systemManufacturer=if($system.Manufacturer){[string]$system.Manufacturer}else{[string](Get-FxToolsRegistryValue $biosRegistryPath 'SystemManufacturer')};
                $systemModel=if($system.Model){[string]$system.Model}else{[string](Get-FxToolsRegistryValue $biosRegistryPath 'SystemProductName')};
                if([string]::IsNullOrWhiteSpace($systemModel)){$systemModel=[string](Get-FxToolsRegistryValue $biosRegistryPath 'BaseBoardProduct')}
                $boardManufacturer=if($board.Manufacturer){[string]$board.Manufacturer}else{[string](Get-FxToolsRegistryValue $biosRegistryPath 'BaseBoardManufacturer')};
                $boardProduct=if($board.Product){[string]$board.Product}else{[string](Get-FxToolsRegistryValue $biosRegistryPath 'BaseBoardProduct')};
                $processorName=if($processor.Name){[string]$processor.Name}else{[string](Get-FxToolsRegistryValue $cpuRegistryPath 'ProcessorNameString')};
                $processorCores=[int]$processor.NumberOfCores;
                $processorLogicalProcessors=[int]$processor.NumberOfLogicalProcessors;
                if($processorLogicalProcessors -le 0){$processorLogicalProcessors=[Environment]::ProcessorCount}
                $processorMaxClockMhz=[int]$processor.MaxClockSpeed;
                if($processorMaxClockMhz -le 0){$processorMaxClockMhz=[int](Get-FxToolsRegistryValue $cpuRegistryPath '~MHz')}
                $totalPhysicalMemoryBytes=[long]$system.TotalPhysicalMemory;
                if($totalPhysicalMemoryBytes -le 0){
                    try {
                        Add-Type -AssemblyName Microsoft.VisualBasic -ErrorAction Stop;
                        $computerInfo=New-Object Microsoft.VisualBasic.Devices.ComputerInfo;
                        $totalPhysicalMemoryBytes=[long]$computerInfo.TotalPhysicalMemory
                    } catch { $totalPhysicalMemoryBytes=0 }
                }
                if($os){
                    $operatingSystemText=(@([string]$os.Caption,[string]$os.Version,('Build '+[string]$os.BuildNumber)) |
                        Where-Object {$_ -and $_ -ne 'Build '}) -join ' · '
                } else {
                    $windowsProductName=[string](Get-FxToolsRegistryValue $windowsRegistryPath 'ProductName');
                    $windowsDisplayVersion=[string](Get-FxToolsRegistryValue $windowsRegistryPath 'DisplayVersion');
                    $windowsBuild=[string](Get-FxToolsRegistryValue $windowsRegistryPath 'CurrentBuild');
                    [int]$windowsBuildNumber=0;
                    if([int]::TryParse($windowsBuild,[ref]$windowsBuildNumber) -and
                        $windowsBuildNumber -ge 22000 -and $windowsProductName -match '^Windows 10'){
                        $windowsProductName=$windowsProductName -replace '^Windows 10','Windows 11'
                    }
                    $operatingSystemText=(@($windowsProductName,$windowsDisplayVersion,('Build '+$windowsBuild)) |
                        Where-Object {$_ -and $_ -ne 'Build '}) -join ' · '
                }
                $graphics=[System.Collections.Generic.List[object]]::new();
                $temperatures=[System.Collections.Generic.List[object]]::new();
                $nvidiaNames=[System.Collections.Generic.List[string]]::new();
                $nvidiaSmi=Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue;
                if($nvidiaSmi){
                    $nvidiaRows=@(& $nvidiaSmi.Source --query-gpu=name,memory.total,driver_version,temperature.gpu --format=csv,noheader,nounits 2>$null);
                    if($LASTEXITCODE -eq 0){
                        foreach($row in $nvidiaRows){
                            $parts=@($row -split ',' | ForEach-Object {$_.Trim()});
                            if($parts.Count -ge 4){
                                [double]$memoryMiB=0; [double]$temperature=0;
                                $memoryOk=[double]::TryParse($parts[1],[System.Globalization.NumberStyles]::Float,[System.Globalization.CultureInfo]::InvariantCulture,[ref]$memoryMiB);
                                $temperatureOk=[double]::TryParse($parts[3],[System.Globalization.NumberStyles]::Float,[System.Globalization.CultureInfo]::InvariantCulture,[ref]$temperature);
                                $nvidiaNames.Add($parts[0]);
                                $graphics.Add([pscustomobject]@{
                                    name=$parts[0];
                                    adapterMemoryBytes=if($memoryOk){[long]($memoryMiB * 1MB)}else{0};
                                    driverVersion=$parts[2];
                                    temperatureCelsius=if($temperatureOk){[math]::Round($temperature,1)}else{$null};
                                    temperatureSource='NVIDIA-SMI'
                                })
                            }
                        }
                    }
                }
                $videoControllers=@(Get-FxToolsCimInstances 'Win32_VideoController');
                if($videoControllers.Count -eq 0){
                    $videoRegistryRoot='HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Video';
                    $videoControllers=@(Get-ChildItem -LiteralPath $videoRegistryRoot -ErrorAction SilentlyContinue |
                        ForEach-Object { Get-ChildItem -LiteralPath $_.PSPath -ErrorAction SilentlyContinue } |
                        ForEach-Object { Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue } |
                        Where-Object {$_.DriverDesc} |
                        Sort-Object DriverDesc -Unique |
                        ForEach-Object {
                            [pscustomobject]@{
                                Name=[string]$_.DriverDesc;
                                AdapterRAM=0;
                                DriverVersion=[string]$_.DriverVersion
                            }
                        })
                }
                foreach($video in $videoControllers){
                    if($nvidiaNames -contains [string]$video.Name){continue}
                    $graphics.Add([pscustomobject]@{
                        name=[string]$video.Name;
                        adapterMemoryBytes=[long]$video.AdapterRAM;
                        driverVersion=[string]$video.DriverVersion;
                        temperatureCelsius=$null;
                        temperatureSource=''
                    })
                }
                function Get-FxToolsTemperatureSensors([string]$namespace) {
                    if(-not $script:fxToolsCimAvailable){return @()}
                    try {
                        return @(Get-CimInstance -Namespace $namespace -ClassName Sensor -ErrorAction Stop |
                            Where-Object {$_.SensorType -eq 'Temperature' -and $null -ne $_.Value})
                    } catch { return @() }
                }
                $sensorSource='LibreHardwareMonitor';
                $sensors=@(Get-FxToolsTemperatureSensors 'root\\LibreHardwareMonitor');
                if($sensors.Count -eq 0){
                    $sensorSource='OpenHardwareMonitor';
                    $sensors=@(Get-FxToolsTemperatureSensors 'root\\OpenHardwareMonitor')
                }
                foreach($sensor in $sensors | Select-Object -First 12){
                    $temperature=[double]$sensor.Value;
                    if($temperature -ge -20 -and $temperature -le 150){
                        $temperatures.Add([pscustomobject]@{
                            label=if([string]::IsNullOrWhiteSpace([string]$sensor.Name)){'温度传感器'}else{[string]$sensor.Name};
                            celsius=[math]::Round($temperature,1);
                            source=$sensorSource
                        })
                    }
                }
                if($temperatures.Count -eq 0){
                    $thermalZones=@(Get-FxToolsCimInstances 'MSAcpi_ThermalZoneTemperature' 'root\\wmi');
                    $zoneNumber=0;
                    foreach($zone in $thermalZones | Select-Object -First 6){
                        $temperature=([double]$zone.CurrentTemperature / 10.0) - 273.15;
                        if($temperature -ge -20 -and $temperature -le 150){
                            $zoneNumber++;
                            $temperatures.Add([pscustomobject]@{
                                label='ACPI 温区 '+$zoneNumber;
                                celsius=[math]::Round($temperature,1);
                                source='ACPI'
                            })
                        }
                    }
                }
                [uint32]$firmwareType=0;
                try {
                    Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public static class FxToolsFirmwareProbe { [DllImport("kernel32.dll")] public static extern bool GetFirmwareType(out uint firmwareType); }' -ErrorAction Stop;
                    if(-not [FxToolsFirmwareProbe]::GetFirmwareType([ref]$firmwareType)){$firmwareType=0}
                } catch {
                    $firmwareType=(Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control' -Name PEFirmwareType -ErrorAction SilentlyContinue).PEFirmwareType;
                    if($null -eq $firmwareType){$firmwareType=0}
                }
                function Test-FxToolsCimClass([string]$namespace,[string]$className) {
                    if(-not $script:fxToolsCimAvailable){return $false}
                    try { return $null -ne (Get-CimClass -Namespace $namespace -ClassName $className -ErrorAction Stop) }
                    catch { return $false }
                }
                $interfaces=[System.Collections.Generic.List[string]]::new();
                if(Test-FxToolsCimClass 'root\\wmi' 'Lenovo_SetBiosSetting'){$interfaces.Add('Lenovo WMI')}
                if(Test-FxToolsCimClass 'root\\HP\\InstrumentedBIOS' 'HP_BIOSSettingInterface'){$interfaces.Add('HP BIOS WMI')}
                if(Test-FxToolsCimClass 'root\\dcim\\sysman' 'DCIM_BIOSService'){$interfaces.Add('Dell DCIM')}
                try {$powerStatesLines=@(& powercfg.exe /a 2>&1)}
                catch {$powerStatesLines=@($_.Exception.Message)}
                $powerStatesText=($powerStatesLines -join [Environment]::NewLine).Trim();
                $activePowerPlan='';
                try {
                    $activePowerPlanText=(@(& powercfg.exe /getactivescheme 2>&1) -join ' ').Trim();
                    if($activePowerPlanText -match '\\(([^()]*)\\)\\s*$'){
                        $activePowerPlan=$matches[1].Trim()
                    } else {
                        $activePowerPlan=$activePowerPlanText
                    }
                } catch { $activePowerPlan='' }
                $powerSupplyStatus='供电状态未知';
                try {
                    if(-not ('FxToolsPowerStatusProbe' -as [type])){
                        Add-Type -TypeDefinition 'using System.Runtime.InteropServices; [StructLayout(LayoutKind.Sequential)] public struct FxToolsSystemPowerStatus { public byte ACLineStatus; public byte BatteryFlag; public byte BatteryLifePercent; public byte SystemStatusFlag; public int BatteryLifeTime; public int BatteryFullLifeTime; } public static class FxToolsPowerStatusProbe { [DllImport("kernel32.dll")] public static extern bool GetSystemPowerStatus(out FxToolsSystemPowerStatus status); }' -ErrorAction Stop
                    }
                    $systemPowerStatus=New-Object FxToolsSystemPowerStatus;
                    if([FxToolsPowerStatusProbe]::GetSystemPowerStatus([ref]$systemPowerStatus)){
                        $supply=if($systemPowerStatus.ACLineStatus -eq 1){'交流供电'}elseif($systemPowerStatus.ACLineStatus -eq 0){'电池供电'}else{'供电来源未知'};
                        if($systemPowerStatus.BatteryFlag -eq 255 -or ($systemPowerStatus.BatteryFlag -band 128)){
                            $powerSupplyStatus=$supply+' · 未检测到电池'
                        } else {
                            $charge=if($systemPowerStatus.BatteryLifePercent -eq 255){'电量未知'}else{[string]$systemPowerStatus.BatteryLifePercent+'%'};
                            $state=if($systemPowerStatus.BatteryFlag -band 8){'充电中'}elseif($systemPowerStatus.BatteryFlag -band 2){'电量严重不足'}elseif($systemPowerStatus.BatteryFlag -band 1){'低电量'}elseif($systemPowerStatus.ACLineStatus -eq 0){'放电中'}else{'已接通电源'};
                            $powerSupplyStatus=$supply+' · '+$charge+' · '+$state
                        }
                    }
                } catch { $powerSupplyStatus='供电状态未知' }
                $hibernateValue=(Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Power' -Name HibernateEnabled -ErrorAction SilentlyContinue).HibernateEnabled;
                try {$wakeDevices=@(& powercfg.exe /devicequery wake_armed 2>$null) | ForEach-Object {$_.ToString().Trim()} | Where-Object {$_ -and $_ -ne 'NONE'}}
                catch {$wakeDevices=@()}
                $ErrorActionPreference='Continue';
                $wakeTimerLines=@(& powercfg.exe /waketimers 2>&1);
                $wakeTimerExitCode=$LASTEXITCODE;
                $ErrorActionPreference='Stop';
                $wakeTimerText=($wakeTimerLines -join [Environment]::NewLine).Trim();
                [pscustomobject]@{
                    systemManufacturer=$systemManufacturer;
                    systemModel=$systemModel;
                    serialNumber=[string]$bios.SerialNumber;
                    systemUuid=[string]$product.UUID;
                    boardManufacturer=$boardManufacturer;
                    boardProduct=$boardProduct;
                    boardSerialNumber=[string]$board.SerialNumber;
                    processorName=$processorName;
                    processorCores=$processorCores;
                    processorLogicalProcessors=$processorLogicalProcessors;
                    processorMaxClockMhz=$processorMaxClockMhz;
                    totalPhysicalMemoryBytes=$totalPhysicalMemoryBytes;
                    memoryModules=@($memoryModules);
                    graphicsAdapters=@($graphics);
                    temperatures=@($temperatures);
                    operatingSystem=$operatingSystemText;
                    biosManufacturer=if($bios.Manufacturer){[string]$bios.Manufacturer}else{[string](Get-FxToolsRegistryValue $biosRegistryPath 'BIOSVendor')};
                    biosVersion=if($bios.SMBIOSBIOSVersion){[string]$bios.SMBIOSBIOSVersion}else{[string](Get-FxToolsRegistryValue $biosRegistryPath 'BIOSVersion')};
                    biosReleaseDate=if($bios.ReleaseDate){$bios.ReleaseDate.ToString('yyyy-MM-dd HH:mm:ss')}else{try{[datetime]::ParseExact([string](Get-FxToolsRegistryValue $biosRegistryPath 'BIOSReleaseDate'),'MM/dd/yyyy',[System.Globalization.CultureInfo]::InvariantCulture).ToString('yyyy-MM-dd')}catch{[string](Get-FxToolsRegistryValue $biosRegistryPath 'BIOSReleaseDate')}};
                    activePowerPlan=$activePowerPlan;
                    powerSupplyStatus=$powerSupplyStatus;
                    firmwareType=[int]$firmwareType;
                    hibernationConfigured=([int]$hibernateValue -eq 1);
                    powerStatesText=$powerStatesText;
                    wakeDevices=@($wakeDevices);
                    wakeTimerExitCode=[int]$wakeTimerExitCode;
                    wakeTimerText=$wakeTimerText;
                    biosManagementInterfaces=@($interfaces)
                } | ConvertTo-Json -Compress -Depth 6;
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

    private List<MemoryModuleInfo> memoryModules(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<MemoryModuleInfo> modules = new ArrayList<>();
        node.forEach(item -> modules.add(new MemoryModuleInfo(
                text(item, "manufacturer"), text(item, "partNumber"),
                Math.max(0, item.path("capacityBytes").asLong(0)),
                Math.max(0, item.path("speedMhz").asInt(0)),
                Math.max(0, item.path("configuredSpeedMhz").asInt(0)))));
        return List.copyOf(modules);
    }

    private List<GraphicsAdapterInfo> graphicsAdapters(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<GraphicsAdapterInfo> adapters = new ArrayList<>();
        node.forEach(item -> adapters.add(new GraphicsAdapterInfo(
                text(item, "name"),
                Math.max(0, item.path("adapterMemoryBytes").asLong(0)),
                text(item, "driverVersion"),
                numberOrUnavailable(item.path("temperatureCelsius")),
                text(item, "temperatureSource"))));
        return List.copyOf(adapters);
    }

    private List<TemperatureReading> temperatureReadings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<TemperatureReading> readings = new ArrayList<>();
        node.forEach(item -> {
            double celsius = numberOrUnavailable(item.path("celsius"));
            if (!Double.isNaN(celsius)) {
                readings.add(new TemperatureReading(text(item, "label"), celsius,
                        text(item, "source")));
            }
        });
        return List.copyOf(readings);
    }

    private static double numberOrUnavailable(JsonNode node) {
        return node.isNumber() ? node.asDouble() : Double.NaN;
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

    public record MemoryModuleInfo(String manufacturer, String partNumber,
                                   long capacityBytes, int speedMhz,
                                   int configuredSpeedMhz) {
    }

    public record GraphicsAdapterInfo(String name, long adapterMemoryBytes,
                                      String driverVersion, double temperatureCelsius,
                                      String temperatureSource) {
        public boolean hasTemperature() {
            return !Double.isNaN(temperatureCelsius);
        }
    }

    public record TemperatureReading(String label, double celsius, String source) {
    }

    public record PowerDiagnostics(String systemManufacturer,
                                   String systemModel,
                                   String serialNumber,
                                   String systemUuid,
                                   String boardManufacturer,
                                   String boardProduct,
                                   String boardSerialNumber,
                                   String processorName,
                                   int processorCores,
                                   int processorLogicalProcessors,
                                   int processorMaxClockMhz,
                                   long totalPhysicalMemoryBytes,
                                   List<MemoryModuleInfo> memoryModules,
                                   List<GraphicsAdapterInfo> graphicsAdapters,
                                   List<TemperatureReading> temperatures,
                                   String operatingSystem,
                                    String biosManufacturer,
                                    String biosVersion,
                                    String biosReleaseDate,
                                    String activePowerPlan,
                                    String powerSupplyStatus,
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
        CommandResult run(String script) throws IOException;
    }

    record CommandResult(int exitCode, String output) {
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(String script) throws IOException {
            PowerShellScriptRunner.Result result = PowerShellScriptRunner.run(
                    script, COMMAND_TIMEOUT_SECONDS,
                    "Windows 电源能力检测", "WindowsPowerDiagnosticsOutput");
            return new CommandResult(result.exitCode(), result.output());
        }
    }
}
