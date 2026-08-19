# WinUI Architecture

## Solution Layout

```text
FxTools.slnx
src/
  FxTools.App/          WinUI 3 shell, pages, dialogs, tray integration
  FxTools.Core/         models, stores, services, validation, bounded buffers
tests/
  FxTools.Core.Tests/   xUnit tests without a WinUI dependency
docs/winui/             requirements, migration plan, and parity matrix
```

## Dependency Direction

`FxTools.App` depends on `FxTools.Core`. Core does not reference WinUI. Windows-specific
operations are exposed as interfaces and implemented in Core using supported .NET APIs or
small, reviewed P/Invoke boundaries. UI pages consume asynchronous services and cancellation
tokens; they do not launch commands or manipulate files directly.

## Runtime Composition

- `ApplicationServices` owns application-lifetime settings and reminder services and their disposal order.
- `MainWindow` owns the `NavigationView`, lazy page cache, settings surface, and global log.
- Transient page operations use per-operation `CancellationTokenSource` instances.
- Persistent monitors implement `IAsyncDisposable` and are explicitly started/stopped.
- `BoundedTextBuffer` and bounded channels cap producer traffic before UI dispatch.
- Stores use `AtomicJsonStore` with atomic UTF-8 writes and Java-compatible options.

## Data Contracts

System.Text.Json uses case-insensitive property reads and camel-case writes. Model defaults are
applied after deserialization so files produced by prior releases remain valid. Store file names
remain unchanged. New schema fields are optional and must have backward-compatible defaults.

## Error Model

Expected failures return typed results with a user-facing message. Exceptions are reserved for
programming errors and unrecoverable initialization. The UI reports errors through an InfoBar and
the bounded global log without exposing stack traces by default.

## Packaging

Development uses an unpackaged x64 WinUI application for fast iteration. `build-exe.bat` publishes
the .NET runtime and Windows App SDK self-contained for x64. MSIX packaging and signing are separate
release steps so local development does not depend on a certificate.
