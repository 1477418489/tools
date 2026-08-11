# Log Monitor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent single-file log monitor with extensible contains/token/regex rules and aggregated JavaFX popup alerts for newly appended matching lines.

**Architecture:** Keep file tailing, rule matching, persistence, polling, alert aggregation, and JavaFX presentation in separate classes. A daemon scheduler reads new complete lines every 500 ms and emits immutable match events; a JavaFX adapter aggregates those events with per-rule 60-second cooldowns.

**Tech Stack:** Java 23, JavaFX 23, Jackson 2.19, JUnit Jupiter 5, Maven Surefire

---

## File Structure

Create focused model files `LogMatchMode.java`, `LogMonitorRule.java`, `LogMonitorConfig.java`, and `LogMonitorMatch.java`; service files `LogMonitorMatcher.java`, `LogFileTailer.java`, `LogMonitorStore.java`, `LogMonitorStatus.java`, `LogMonitorService.java`, `LogAlertAccumulator.java`, and `LogMonitorAlertService.java`; controller/FXML files `LogMonitorController.java` and `log-monitor-view.fxml`; and corresponding tests. Modify `JarStartupLogTailer.java`, `MainController.java`, `main-view.fxml`, `modern-light.css`, `FxmlLayoutRegressionTest.java`, and `README.md` only where the feature connects to existing behavior.

### Task 1: Rule Models And Matcher

**Files:**
- Create: `src/main/java/plugin/javafxtools/model/LogMatchMode.java`
- Create: `src/main/java/plugin/javafxtools/model/LogMonitorRule.java`
- Create: `src/main/java/plugin/javafxtools/model/LogMonitorConfig.java`
- Create: `src/main/java/plugin/javafxtools/model/LogMonitorMatch.java`
- Create: `src/main/java/plugin/javafxtools/service/LogMonitorMatcher.java`
- Test: `src/test/java/plugin/javafxtools/service/LogMonitorMatcherTest.java`

- [ ] **Step 1: Write the failing matcher tests**

Use the intended API and test multi-character contains, case-insensitive contains, Unicode token boundaries, regex `find()`, disabled rules, one result per rule per line, empty expressions, invalid regex, and duplicate definitions:

```java
LogMonitorRule rule = new LogMonitorRule("rate-limit", "限流",
        "Too Many Requests", LogMatchMode.CONTAINS, false, true);
LogMonitorMatcher matcher = new LogMonitorMatcher(List.of(rule));
assertEquals(List.of(rule), matcher.matchingRules("HTTP: too many requests"));
```

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=LogMonitorMatcherTest test`.
Expected: compilation fails because the new model/matcher types do not exist.

- [ ] **Step 3: Implement the minimal domain API and matcher**

```java
public enum LogMatchMode { CONTAINS, WHOLE_TOKEN, REGEX }

public record LogMonitorRule(String id, String name, String expression,
        LogMatchMode mode, boolean caseSensitive, boolean enabled) { }

public record LogMonitorConfig(boolean enabled, String logFile,
        List<LogMonitorRule> rules) {
    public static LogMonitorConfig defaults() { /* user Desktop/cc-switch.log + 429/503 */ }
}

public record LogMonitorMatch(String ruleId, String ruleName, String expression,
        Path logFile, String line, Instant matchedAt) { }
```

`LogMonitorMatcher(List<LogMonitorRule>)` compiles enabled rules once and exposes `matchingRules(String)`. Use `Locale.ROOT` case folding; treat Unicode letters, digits, and `_` as token characters; use regex `find()`; reject blank expressions and duplicate `(mode, expression, caseSensitive)` definitions.

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=LogMonitorMatcherTest test`; expect PASS.
Commit with `git commit -m "feat: add extensible log matching rules"` after staging only Task 1 files.

### Task 2: Generic Incremental Tailer

**Files:**
- Create: `src/main/java/plugin/javafxtools/service/LogFileTailer.java`
- Modify: `src/main/java/plugin/javafxtools/service/JarStartupLogTailer.java`
- Test: `src/test/java/plugin/javafxtools/service/LogFileTailerTest.java`
- Test: `src/test/java/plugin/javafxtools/service/JarStartupLogTailerTest.java`

- [ ] **Step 1: Write failing tests against these constructors**

```java
LogFileTailer follower = LogFileTailer.followNewContent(log);
LogFileTailer fromStart = new LogFileTailer(log, 0L);
```

Test ignored history, appended complete lines, split UTF-8, CRLF, ANSI removal, 32 KiB truncation, absent-file first appearance, file truncation, and same-path replacement without sleeps.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=LogFileTailerTest test`.
Expected: compilation fails because `LogFileTailer` is missing.

- [ ] **Step 3: Implement and delegate**

Expose `LogFileTailer(Path,long)`, `followNewContent(Path)`, and synchronized `readAvailable(boolean)`. Track `BasicFileAttributes.fileKey()`, position, pending UTF-8 bytes, and first-observation skip-to-end. Reset on replacement or size shrink. Keep the existing 8 KiB buffer, 32 KiB line cap, CR removal, ANSI removal, and immutable results. Make `JarStartupLogTailer` delegate to `new LogFileTailer(logFile, startOffset)` without changing its public API.

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=LogFileTailerTest,JarStartupLogTailerTest test`; expect PASS.
Commit with `git commit -m "refactor: add reusable incremental log tailer"`.

### Task 3: Configuration Persistence

**Files:**
- Create: `src/main/java/plugin/javafxtools/service/LogMonitorStore.java`
- Test: `src/test/java/plugin/javafxtools/service/LogMonitorStoreTest.java`

- [ ] **Step 1: Write failing store tests**

```java
LogMonitorStore store = new LogMonitorStore(tempDir.resolve("log-monitor.json"));
assertEquals(LogMonitorConfig.defaults(), store.load());
store.save(config);
assertEquals(config, store.load());
```

Also test malformed JSON, unknown/missing fields, invalid enum, blank expression, duplicate rules, and defensive rule lists.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=LogMonitorStoreTest test`.
Expected: compilation fails because `LogMonitorStore` is missing.

- [ ] **Step 3: Implement strict load/save**

The public constructor targets `AppDataPaths.dataFile("log-monitor.json")`; a package-visible `LogMonitorStore(Path)` supports tests. Validate exactly `enabled`, `logFile`, `rules` at root and the six documented fields per rule. Reuse `LogMonitorMatcher` validation and write only through `AtomicFileWriter.writeUtf8`.

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=LogMonitorStoreTest,AtomicFileWriterTest test`; expect PASS.
Commit with `git commit -m "feat: persist log monitor configuration"`.

### Task 4: Polling Monitor Service

**Files:**
- Create: `src/main/java/plugin/javafxtools/service/LogMonitorStatus.java`
- Create: `src/main/java/plugin/javafxtools/service/LogMonitorService.java`
- Test: `src/test/java/plugin/javafxtools/service/LogMonitorServiceTest.java`

- [ ] **Step 1: Write failing asynchronous tests**

Use temp files and `CountDownLatch`, with a package-visible constructor accepting poll milliseconds and `Clock`. Test waiting for an absent file, history ignored, appended match emitted, truncation recovery, idempotent start/stop, duplicate error suppression, and no callbacks after close.

```java
interface Listener {
    void onStatusChanged(LogMonitorStatus status, String detail);
    void onMatches(List<LogMonitorMatch> matches);
    void onReadError(String message);
}
```

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=LogMonitorServiceTest test`.
Expected: compilation fails because service/status types are missing.

- [ ] **Step 3: Implement the generation-safe poller**

```java
public enum LogMonitorStatus { STOPPED, WAITING_FOR_FILE, RUNNING, ERROR }

public final class LogMonitorService implements AutoCloseable {
    public synchronized void start(LogMonitorConfig config, Listener listener);
    public synchronized void stop();
    public synchronized boolean isRunning();
    @Override public void close();
}
```

Use one daemon scheduled executor, default 500 ms fixed delay, immutable config/rules, `LogFileTailer.followNewContent`, and clock-based event timestamps. Publish status only when `(status, detail)` changes. Check a generation token before every callback so stopped sessions cannot deliver late events.

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=LogMonitorServiceTest,LogMonitorMatcherTest,LogFileTailerTest test` twice; expect PASS both times.
Commit with `git commit -m "feat: poll appended log lines for matches"`.

### Task 5: Alert Aggregation State Machine

**Files:**
- Create: `src/main/java/plugin/javafxtools/service/LogAlertAccumulator.java`
- Test: `src/test/java/plugin/javafxtools/service/LogAlertAccumulatorTest.java`

- [ ] **Step 1: Write failing deterministic tests**

```java
LogAlertAccumulator accumulator = new LogAlertAccumulator(Duration.ofSeconds(60), 3);
assertEquals(LogAlertAccumulator.Action.SHOW,
        accumulator.onMatch(match429, now).action());
accumulator.onDialogClosed(now.plusSeconds(2));
assertEquals(LogAlertAccumulator.Action.NONE,
        accumulator.onMatch(match429, now.plusSeconds(10)).action());
assertEquals(LogAlertAccumulator.Action.SHOW,
        accumulator.cooldownElapsed(now.plusSeconds(62)).action());
```

Cover dialog-open updates, per-rule cooldown, pending summaries, merged different rules, and at most three retained lines per rule.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=LogAlertAccumulatorTest test`.
Expected: compilation fails because the accumulator is missing.

- [ ] **Step 3: Implement pure state**

Expose nested immutable `Action { NONE, SHOW, UPDATE }`, `RuleSummary`, `Snapshot`, and `Change` values. Track active-dialog buckets, pending cooldown buckets, and `blockedUntil` per rule. Every `Change` includes the current snapshot and earliest next wake-up `Instant`.

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=LogAlertAccumulatorTest test`; expect PASS without sleeps.
Commit with `git commit -m "feat: aggregate log alerts with cooldown"`.

### Task 6: JavaFX Alert Adapter

**Files:**
- Create: `src/main/java/plugin/javafxtools/service/LogMonitorAlertService.java`
- Test: `src/test/java/plugin/javafxtools/service/LogMonitorAlertServiceTest.java`

- [ ] **Step 1: Write failing adapter tests**

Inject an FX dispatcher, `Clock`, sound supplier, and fake dialog presenter. Verify one SHOW dispatch, UPDATE while open, a single cooldown wake-up, no beep on update, and timer cancellation on close.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=LogMonitorAlertServiceTest test`.
Expected: compilation fails because the alert service is missing.

- [ ] **Step 3: Implement the adapter**

```java
public final class LogMonitorAlertService implements AutoCloseable {
    public void accept(LogMonitorMatch match);
    public void setOwner(Stage owner);
    public void setSoundEnabledSupplier(BooleanSupplier supplier);
    @Override public void close();
}
```

Use one daemon scheduler only for cooldown wake-ups and dispatch all UI work through `Platform.runLater`. Keep one non-blocking themed `Dialog<ButtonType>`, update its wrapped summary content in place, raise/de-iconify the owner, set the dialog stage always on top, beep once per SHOW when enabled, and notify the accumulator from `setOnHidden`.

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=LogMonitorAlertServiceTest,LogAlertAccumulatorTest test`; expect PASS without starting the JavaFX toolkit in fake-presenter tests.
Commit with `git commit -m "feat: show aggregated log monitor alerts"`.

### Task 7: Monitor Page And Controller

**Files:**
- Create: `src/main/java/plugin/javafxtools/controller/LogMonitorController.java`
- Create: `src/main/resources/plugin/javafxtools/log-monitor-view.fxml`
- Test: `src/test/java/plugin/javafxtools/LogMonitorFxmlTest.java`

- [ ] **Step 1: Write a failing FXML/controller contract test**

Parse FXML as XML and assert controller name, path chooser, save/start/stop buttons, rule editor, mode combo, rule table, match table, status label, and exact handlers. Reflect over the controller for `initialize`, `cleanup`, `setPrimaryStage`, and `setReminderSoundEnabledSupplier`.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=LogMonitorFxmlTest test`.
Expected: failure because page/controller files are absent.

- [ ] **Step 3: Add the full-width FXML workspace**

Use existing command-surface/table styles: compact path/status commands at top, a stable split layout with rule table and recent-match table, and a compact rule editor. Use `ComboBox<LogMatchMode>` for mode, checkboxes for binary fields, fixed table columns, and bounded/wrapped log content.

- [ ] **Step 4: Implement controller orchestration**

`LogMonitorController extends BaseController`, loads `LogMonitorStore`, owns monitor/alert services, caps recent matches at 200, and exposes:

```java
public void setPrimaryStage(Stage stage);
public void setReminderSoundEnabledSupplier(BooleanSupplier supplier);
@Override public void cleanup();
```

Handlers select file, add/update/delete rules, validate and save, and start/stop. Saving while running stops, persists, and restarts only after successful save. Listener callbacks batch through `Platform.runLater` and ignore events after cleanup.

- [ ] **Step 5: Verify GREEN and commit**

Run `mvn -q -Dtest=LogMonitorFxmlTest,LogMonitorStoreTest,LogMonitorServiceTest test`; expect PASS.
Commit with `git commit -m "feat: add log monitor workspace"`.

### Task 8: Main UI Integration And Styling

**Files:**
- Modify: `src/main/java/plugin/javafxtools/controller/MainController.java`
- Modify: `src/main/resources/plugin/javafxtools/main-view.fxml`
- Modify: `src/main/resources/css/modern-light.css`
- Modify: `src/test/java/plugin/javafxtools/FxmlLayoutRegressionTest.java`

- [ ] **Step 1: Extend the FXML regression test first**

Require `logMonitorNavButton`, `logMonitorTab`, `log-monitor-view.fxml` registration, eager startup loading, and primary-stage/sound injection.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=FxmlLayoutRegressionTest test`.
Expected: assertion failure for missing log monitor navigation/tab.

- [ ] **Step 3: Integrate the module**

Add the button under “系统与开发”, add the tab, register `("系统与开发", "日志监控", "log-monitor-view.fxml")`, map navigation, and call `loadModule(logMonitorTab)` during initialization. Add a loaded-controller getter, propagate `primaryStage`, and inject the reminder sound supplier in `configureLoadedController`.

- [ ] **Step 4: Add focused styles**

Add monitor page/status/expression/row-height classes using the existing light palette and existing command/table controls. Do not add nested cards or a new color theme.

- [ ] **Step 5: Verify GREEN and commit**

Run `mvn -q -Dtest=FxmlLayoutRegressionTest,LogMonitorFxmlTest test`; expect PASS.
Commit with `git commit -m "feat: integrate persistent log monitoring"`.

### Task 9: Documentation And Verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document behavior**

Add the module, `log-monitor-view.fxml`, `log-monitor.json`, contains/token/regex behavior, new-line-only reading, popup aggregation, and background-resource notes.

- [ ] **Step 2: Run focused tests twice**

Run twice:

```powershell
mvn -q -Dtest=LogMonitorMatcherTest,LogFileTailerTest,LogMonitorStoreTest,LogMonitorServiceTest,LogAlertAccumulatorTest,LogMonitorAlertServiceTest,LogMonitorFxmlTest,FxmlLayoutRegressionTest test
```

Expected: both runs pass without failures or warnings.

- [ ] **Step 3: Run full verification**

Run `mvn -q test` and `mvn -q -DskipTests compile`.
Expected: both commands exit 0.

- [ ] **Step 4: Manual smoke test**

Run the app, select `C:\Users\Admin\Desktop\cc-switch.log`, and append:

```text
request completed with status=200
request rejected with status=429
upstream response: 503 Service Unavailable
request failed: Too Many Requests
```

Confirm 200 is quiet; 429/503 alert; a `CONTAINS` rule catches the phrase; repeated matches aggregate; stop prevents new alerts; and app exit removes the monitor thread/dialog.

- [ ] **Step 5: Commit docs and inspect state**

Commit `README.md` with `git commit -m "docs: describe log monitoring alerts"`. Run `git status --short` and `git log -10 --oneline --decorate`; expect only the user's pre-existing `.idea/workspace.xml` modification to remain.
