# WinUI Migration Plan

Status: completed on the `winUI` branch. The phases below are retained as the implementation record.

## Phase 1 - Foundation

- Create the .NET solution, shared build settings, Core tests, and WinUI shell.
- Implement app-data paths, atomic JSON stores, bounded logs, cancellation helpers, and results.
- Add compatibility fixtures for every existing JSON store.

## Phase 2 - Local Utilities

- Data formatter, string tools, Base64, file analysis, environment inspection.
- Process/port snapshot and guarded termination.
- Validate streaming and bounded-memory behavior with automated tests.

## Phase 3 - Network Utilities

- HTTP request client, templates, formatter, and scheduler.
- WebSocket client with bounded history.
- Network lookup, quality probes, proxy support, and keep-alive scheduling.

## Phase 4 - Operations

- Application launcher and visibility-aware status refresh.
- JAR copy/launch/stop/log/port workflows.
- Power schedules and firmware diagnostics.
- Reminder scheduler, notification dialog, snooze, sound, and persistence.

## Phase 5 - Log Automation

- Bounded UTF-8 tailer and matcher parity.
- Aggregated alert state machine and cooldowns.
- Multi-rule automation queue and remote response gate.
- Native window picker, foreground verification, Unicode `SendInput`, and PID/title safety.

## Phase 6 - Desktop Integration

- Settings, startup registration, tray behavior, open data directory, and ZIP backup.
- Lazy page activation and deterministic shutdown.
- Accessibility, keyboard flow, scaling, and theme verification.

## Phase 7 - Cutover

- Complete the feature matrix and regression tests.
- Produce Release x64 output and record startup/idle/high-load memory.
- Remove Maven, Java, FXML, and JavaFX packaging only after all parity gates pass.
- Rewrite the root README for the .NET build and release workflow.

## Definition Of Done

- [x] No production feature invokes Java code or requires a JRE.
- [x] No feature row in `feature-matrix.md` remains `Pending`.
- [x] Core tests and x64 builds pass with zero warnings.
- [x] Long-running queues and UI histories remain within documented limits.
