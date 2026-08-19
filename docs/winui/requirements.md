# FxTools WinUI 3 Migration Requirements

## Objective

Replace the JavaFX implementation with a Windows-native C# and WinUI 3 application while
preserving every user-visible workflow, existing persisted data, and long-running behavior.
The migration is complete only when the .NET application is the sole production build.

## Product Scope

FxTools is a local Windows operations toolbox. The WinUI application shall provide:

1. Application launcher management and batch launch.
2. JAR project copy, launch, stop, port ownership, status, folder, and log actions.
3. Windows shutdown, restart, sleep, wake scheduling, and read-only firmware diagnostics.
4. HTTP requests, repeated scheduling, templates, response formatting, copy, and save.
5. WebSocket connect, disconnect, send, and bounded message history.
6. Host/IP lookup and public egress information.
7. HTTP, TCP, TLS, STUN, and proxy-aware network quality monitoring.
8. HTTP/Ping keep-alive targets with randomized intervals and independent state.
9. Process and listening-port snapshots, filtering, detail copy, and guarded termination.
10. Java, Maven, Git, Node, npm, Python, and Docker environment inspection.
11. Streaming file hashes, encoding/type detection, metadata, locking check, and cancellation.
12. Log tailing, multi-rule matching, alerts, multi-rule automation, remote gate, and native input.
13. JSON/XML formatting.
14. Whitespace removal and text case conversion.
15. Standard, URL-safe, and MIME Base64 with selectable text encoding.
16. Repeating and one-time reminders, snooze, pause/resume, completion, sound, and persistence.
17. Global settings, close-to-tray, startup registration, data folder, and ZIP backup.

## Compatibility

- Continue using `%LOCALAPPDATA%\FxTools`.
- Read existing JSON file names and tolerate Java-era field casing.
- Write UTF-8 JSON atomically through a temporary file and replacement.
- Preserve existing data on malformed input; report errors instead of overwriting it.
- Target Windows 11 x64 and Windows 10 build 19041 or newer.

## UI And Interaction

- Use a WinUI `NavigationView` shell with grouped, searchable tools and lazy page creation.
- Use Fluent controls, system typography, theme resources, keyboard navigation, and tooltips.
- Keep operational pages dense and scan-oriented; avoid nested decorative cards.
- Use icons for commands where a standard WinUI symbol exists.
- Do not block the UI thread for file, process, network, DNS, hashing, or command operations.
- Disable conflicting actions while an operation is active and expose cancellation where useful.
- Maintain layout integrity at 1180 x 720 through wide desktop sizes and 125%-200% scaling.

## Performance And Memory

- Idle modules must not poll unless their function requires it.
- Pages that are not visible must pause status polling when safe.
- Logs are bounded to 800 displayed lines and 1,000,000 characters.
- Pending log queues are bounded to 500 entries and 500,000 characters.
- A single displayed log message is bounded to 100,000 characters.
- Log monitor lines are bounded to 32 KiB; each poll reads at most 256 KiB and 200 lines.
- Match UI backlog is bounded to 500 entries and 1,000,000 characters.
- Recent log matches are bounded to 200 entries.
- Automation has one worker and at most 32 pending actions.
- HTTP response bodies are bounded to 200,000 characters.
- Base64 input is bounded to 1,000,000 characters.
- JAR output logs rotate or truncate at 50 MiB.
- All timers, sockets, processes, streams, watchers, and cancellation tokens are disposed.

## Windows Integration And Safety

- Enumerate top-level windows and send keyboard input through Win32 interop, not PowerShell.
- A selected automation target is identified by PID plus exact window title.
- Never fall back from a stale PID to an unrelated process with the same title.
- Verify the target immediately before `SendInput`; do not send if foreground activation fails.
- Refuse ambiguous process/window matches.
- Guard critical/system processes and FxTools itself from termination.
- Validate port ownership before stopping a JAR process.
- Require explicit confirmation for process termination and power operations.
- Do not read or modify BIOS variables; firmware diagnostics remain read-only.

## Quality Gates

- Core services have unit tests for valid, invalid, cancellation, limit, and compatibility cases.
- Every navigation destination loads without an exception.
- Debug and Release x64 builds complete with zero warnings.
- The packaged application starts on a clean supported Windows account.
- A migration matrix records parity evidence for every module before Java removal.

