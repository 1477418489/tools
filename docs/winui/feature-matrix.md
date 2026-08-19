# Feature Parity Matrix

All production modules are implemented in C# and WinUI 3. `Verified` means the Core workflow is covered by automated tests where practical and the WinUI destination loaded successfully in the x64 application.

| Area | Required workflows | Persistence | Status |
|---|---|---|---|
| Shell | Grouped/searchable navigation, lazy pages, settings | `settings.json` | Verified |
| Application launcher | Add/remove, batch launch, stop, status, interval | `app_launcher_paths.json`, `app_launcher_settings.json` | Verified |
| JAR launcher | Project CRUD, copy, port, launch/stop, status, folder/log | `jar_launcher_projects.json` | Verified |
| Power | Shutdown/restart/sleep, wake tasks, diagnostics | Windows Task Scheduler | Verified |
| HTTP | Methods, headers/body, timeout, once/repeat, templates, response tools | `http_templates.json` | Verified |
| WebSocket | Connect/disconnect/send, status, clear bounded history | None | Verified |
| Network lookup | Host/IP and public egress lookup | None | Verified |
| Network quality | HTTP/TCP/TLS/STUN, proxy, trends and report | `network_quality_targets.json`, `network_quality_settings.json` | Verified |
| Keep alive | HTTP/Ping, randomized interval, independent state | `keepAlive.json` | Verified |
| Process/port | Snapshots, filters, copy details, guarded terminate | None | Verified |
| Environment | Java/Maven/Git/Node/npm/Python/Docker report | None | Verified |
| File analysis | Hashes, encoding/type, metadata, lock, cancel | None | Verified |
| Log monitor | Tail, rules, alerts, multi-trigger automation, remote gate | `log-monitor.json` | Verified |
| Data formatter | JSON/XML format and copy | None | Verified |
| String tools | Remove whitespace, upper/lower case, copy | None | Verified |
| Base64 | Standard/URL/MIME, encoding, copy/swap/paste | None | Verified |
| Reminders | Repeating/one-time, pause, snooze, complete, sound | `memo_reminders.json` | Verified |
| Desktop integration | Tray, close behavior, startup, data folder, ZIP backup | `settings.json` | Verified |

## Verification Evidence

- 28 Core tests pass, including legacy JSON, multi-rule matching, tail limits, exact PID/title selectors, JAR argument parsing, reminder scheduling, settings rollback, network probes, and streaming ZIP backup.
- Debug solution build completes with zero warnings and zero errors.
- Self-contained Release x64 publishing completes, includes generated PRI/XBF resources, and starts successfully from `dist/FxTools/FxTools.exe`.
- All 17 tool destinations plus Settings were loaded through Windows UI Automation without a XAML exception.
- Tray close and exact-process tray callback restore were exercised in the built application.
- After all pages were created, eight rounds of key-page switching changed working set from about 235 MiB to 238 MiB and private memory from about 160 MiB to 161 MiB; handles did not grow.
