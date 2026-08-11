package plugin.javafxtools.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Manages one-time Windows power and wake tasks through Task Scheduler.
 */
public final class WindowsPowerSchedulerService {
    static final String POWER_TASK_NAME = "FxTools-PowerAction";
    static final String WAKE_TASK_NAME = "FxTools-Wake";

    private static final DateTimeFormatter TASK_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int COMMAND_TIMEOUT_SECONDS = 20;

    private final boolean supported;
    private final CommandRunner commandRunner;

    public WindowsPowerSchedulerService() {
        this(System.getProperty("os.name", ""), new ProcessCommandRunner());
    }

    WindowsPowerSchedulerService(String osName, CommandRunner commandRunner) {
        supported = osName != null
                && osName.toLowerCase(Locale.ROOT).startsWith("windows");
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    public boolean isSupported() {
        return supported;
    }

    public void schedulePowerAction(LocalDateTime scheduledFor,
                                    PowerAction action) throws IOException {
        requireSupported();
        requireFutureTime(scheduledFor);
        Objects.requireNonNull(action, "action");

        String script = scriptPreamble()
                + connectTaskSchedulerScript()
                + taskTimeDeclaration(scheduledFor)
                + taskDefinitionScript("FxTools scheduled power action", false)
                + "$action=$task.Actions.Create(0);"
                + "$action.Path=\"$env:SystemRoot\\System32\\shutdown.exe\";"
                + "$action.Arguments='" + action.commandArguments() + "';"
                + registerTaskScript(POWER_TASK_NAME);
        runPowerShell(script, "创建电源计划失败");
    }

    public void scheduleWake(LocalDateTime scheduledFor) throws IOException {
        requireSupported();
        requireFutureTime(scheduledFor);

        String script = scriptPreamble()
                + connectTaskSchedulerScript()
                + taskTimeDeclaration(scheduledFor)
                + taskDefinitionScript("FxTools scheduled wake", true)
                + "$action=$task.Actions.Create(0);"
                + "$action.Path=\"$env:SystemRoot\\System32\\cmd.exe\";"
                + "$action.Arguments='/c exit 0';"
                + registerTaskScript(WAKE_TASK_NAME);
        runPowerShell(script, "创建唤醒计划失败");
    }

    public void cancelPowerAction() throws IOException {
        requireSupported();
        String script = scriptPreamble()
                + connectTaskSchedulerScript()
                + unregisterTaskScript(POWER_TASK_NAME)
                + "& \"$env:SystemRoot\\System32\\shutdown.exe\" /a "
                + "2>$null | Out-Null; exit 0;";
        runPowerShell(script, "取消电源计划失败");
    }

    public void cancelWake() throws IOException {
        requireSupported();
        runPowerShell(scriptPreamble() + connectTaskSchedulerScript()
                        + unregisterTaskScript(WAKE_TASK_NAME),
                "取消唤醒计划失败");
    }

    public ScheduledTaskStatus queryPowerTask() throws IOException {
        return queryTask(POWER_TASK_NAME, true);
    }

    public ScheduledTaskStatus queryWakeTask() throws IOException {
        return queryTask(WAKE_TASK_NAME, false);
    }

    private ScheduledTaskStatus queryTask(String taskName,
                                          boolean detectPowerAction) throws IOException {
        requireSupported();
        String script = scriptPreamble()
                + connectTaskSchedulerScript()
                + "try{$task=$root.GetTask('" + taskName + "')}"
                + "catch{if($_.Exception.HResult -in @(-2147024894,-2147024893))"
                + "{Write-Output 'MISSING';exit 0};throw};"
                + "$next=if($task.NextRunTime -and $task.NextRunTime.Year -gt 2000)"
                + "{$task.NextRunTime.ToString('yyyy-MM-ddTHH:mm:ss')}else{''};"
                + "$args=($task.Definition.Actions.Item(1).Arguments -replace '\\|',' ');"
                + "Write-Output ('FOUND|'+$task.State+'|'+$next+'|'+$args);";
        CommandResult result = runPowerShell(script, "读取任务状态失败");
        return parseStatus(result.output(), detectPowerAction);
    }

    private CommandResult runPowerShell(String script,
                                        String failureMessage) throws IOException {
        String guardedScript = "try{" + script
                + "}catch{Write-Output ('ERROR|'+$_.Exception.Message);exit 1}";
        CommandResult result = commandRunner.run(guardedScript);
        if (result.exitCode() != 0) {
            String output = cleanOutput(result.output());
            String details = output.lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("ERROR|"))
                    .map(line -> line.substring("ERROR|".length()).strip())
                    .reduce((previous, current) -> current)
                    .orElse(output);
            throw new IOException(details.isBlank()
                    ? failureMessage + "，退出码: " + result.exitCode()
                    : failureMessage + ": " + details);
        }
        return result;
    }

    private ScheduledTaskStatus parseStatus(String output,
                                            boolean detectPowerAction) throws IOException {
        String statusLine = Arrays.stream(cleanOutput(output).split("\\R"))
                .map(String::strip)
                .filter(line -> line.equals("MISSING") || line.startsWith("FOUND|"))
                .findFirst()
                .orElseThrow(() -> new IOException("任务计划程序返回了无法识别的状态"));
        if (statusLine.equals("MISSING")) {
            return ScheduledTaskStatus.missing();
        }

        String[] parts = statusLine.split("\\|", 4);
        if (parts.length < 3) {
            throw new IOException("任务计划程序状态字段不完整");
        }
        LocalDateTime nextRunTime = null;
        if (!parts[2].isBlank()) {
            try {
                nextRunTime = LocalDateTime.parse(parts[2], TASK_TIME_FORMAT);
            } catch (DateTimeParseException e) {
                throw new IOException("无法解析任务执行时间: " + parts[2], e);
            }
        }
        PowerAction action = detectPowerAction && parts.length == 4
                ? PowerAction.fromCommandArguments(parts[3]) : null;
        return new ScheduledTaskStatus(true, nextRunTime, parts[1], action);
    }

    private String taskTimeDeclaration(LocalDateTime scheduledFor) {
        return "$when='"
                + TASK_TIME_FORMAT.format(scheduledFor.withNano(0))
                + "';";
    }

    private String unregisterTaskScript(String taskName) {
        return "try{$root.DeleteTask('" + taskName + "',0)}"
                + "catch{if($_.Exception.HResult -notin @(-2147024894,-2147024893)){throw}};";
    }

    private String connectTaskSchedulerScript() {
        return "$service=New-Object -ComObject 'Schedule.Service';"
                + "$service.Connect();$root=$service.GetFolder('\\');"
                + "$user=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name;";
    }

    private String taskDefinitionScript(String description, boolean wakeToRun) {
        return "$task=$service.NewTask(0);"
                + "$task.RegistrationInfo.Description='" + description + "';"
                + "$task.Principal.UserId=$user;$task.Principal.LogonType=3;"
                + "$task.Principal.RunLevel=0;$task.Settings.Enabled=$true;"
                + "$task.Settings.StartWhenAvailable=$true;"
                + "$task.Settings.DisallowStartIfOnBatteries=$false;"
                + "$task.Settings.StopIfGoingOnBatteries=$false;"
                + "$task.Settings.WakeToRun=$" + wakeToRun + ";"
                + "$task.Settings.ExecutionTimeLimit='PT5M';"
                + "$trigger=$task.Triggers.Create(1);$trigger.StartBoundary=$when;"
                + "$trigger.Enabled=$true;";
    }

    private String registerTaskScript(String taskName) {
        return "$root.RegisterTaskDefinition('" + taskName
                + "',$task,6,$user,$null,3,$null)|Out-Null;";
    }

    private String scriptPreamble() {
        return "$ErrorActionPreference='Stop';"
                + "[Console]::OutputEncoding="
                + "[System.Text.UTF8Encoding]::new($false);";
    }

    private void requireSupported() throws IOException {
        if (!supported) {
            throw new IOException("电源计划仅支持 Windows 系统");
        }
    }

    private void requireFutureTime(LocalDateTime scheduledFor) {
        Objects.requireNonNull(scheduledFor, "scheduledFor");
        if (!scheduledFor.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("计划时间必须晚于当前时间");
        }
    }

    private String cleanOutput(String output) {
        return output == null ? "" : output.replace("\uFEFF", "").strip();
    }

    public enum PowerAction {
        SHUTDOWN("关机", "/s /t 60 /c \"FxTools scheduled shutdown\""),
        RESTART("重启", "/r /t 60 /c \"FxTools scheduled restart\""),
        HIBERNATE("休眠", "/h");

        private final String displayName;
        private final String commandArguments;

        PowerAction(String displayName, String commandArguments) {
            this.displayName = displayName;
            this.commandArguments = commandArguments;
        }

        public String displayName() {
            return displayName;
        }

        String commandArguments() {
            return commandArguments;
        }

        static PowerAction fromCommandArguments(String arguments) {
            if (arguments == null) {
                return null;
            }
            String normalized = " " + arguments.toLowerCase(Locale.ROOT) + " ";
            if (normalized.contains(" /s ")) {
                return SHUTDOWN;
            }
            if (normalized.contains(" /r ")) {
                return RESTART;
            }
            if (normalized.contains(" /h ") || normalized.strip().endsWith("/h")) {
                return HIBERNATE;
            }
            return null;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public record ScheduledTaskStatus(boolean exists,
                                      LocalDateTime nextRunTime,
                                      String schedulerState,
                                      PowerAction powerAction) {
        static ScheduledTaskStatus missing() {
            return new ScheduledTaskStatus(false, null, "", null);
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
                    "Windows 电源计划操作", "WindowsPowerSchedulerOutput");
            return new CommandResult(result.exitCode(), result.output());
        }
    }
}
