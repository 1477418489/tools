package plugin.javafxtools.service;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import plugin.javafxtools.util.LogTextTrimmer;
import plugin.javafxtools.util.TimeUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 全局日志服务，负责向主界面中央日志区域分发日志。
 */
public class LoggingService {
    /**
     * 单个日志区域保留的最大日志行数。
     */
    private static final int MAX_LOG_LINES = 800;
    private static final int MAX_LOG_CHARACTERS = 1_000_000;
    private static final int LOG_TRIM_TARGET_CHARACTERS = 750_000;
    private static final int MAX_PENDING_LOG_MESSAGES = 500;
    private static final int MAX_PENDING_LOG_CHARACTERS = 500_000;
    private static final int MAX_SINGLE_LOG_CHARACTERS = 100_000;
    private static final int LOG_BATCH_SIZE = 100;

    /**
     * 中央日志区域弱引用列表，避免控制器释放后仍被服务持有。
     */
    private final List<WeakReference<TextArea>> globalLogAreas = new ArrayList<>();
    private final Object pendingLogLock = new Object();
    private final Deque<PendingLog> pendingLogs = new ArrayDeque<>();
    private final Consumer<Runnable> fxDispatcher;
    private final BooleanSupplier fxThreadChecker;
    private final Consumer<String> standardOutput;
    private final Consumer<String> standardError;
    private final AtomicLong logGeneration = new AtomicLong();
    private int pendingLogCharacters;
    private long droppedLogCount;
    private boolean logFlushScheduled;
    private long logFlushToken;

    public LoggingService() {
        this(Platform::runLater, Platform::isFxApplicationThread,
                System.out::println, System.err::println);
    }

    LoggingService(Consumer<Runnable> fxDispatcher,
                   BooleanSupplier fxThreadChecker,
                   Consumer<String> standardOutput,
                   Consumer<String> standardError) {
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher");
        this.fxThreadChecker = Objects.requireNonNull(fxThreadChecker, "fxThreadChecker");
        this.standardOutput = Objects.requireNonNull(standardOutput, "standardOutput");
        this.standardError = Objects.requireNonNull(standardError, "standardError");
    }

    /**
     * 注册全局日志区域。
     *
     * @param logArea 要注册的日志区域
     */
    public synchronized void addGlobalLogArea(TextArea logArea) {
        if (logArea == null) {
            return;
        }
        cleanupReleasedAreas();
        boolean exists = globalLogAreas.stream()
                .map(WeakReference::get)
                .anyMatch(area -> area == logArea);
        if (!exists) {
            globalLogAreas.add(new WeakReference<>(logArea));
        }
    }

    /**
     * 记录信息级别全局日志。
     *
     * @param message 日志内容
     */
    public void info(String message) {
        log("INFO", message);
    }

    /**
     * 记录错误级别全局日志。
     *
     * @param message 日志内容
     */
    public void error(String message) {
        log("ERROR", message);
    }

    /**
     * 向所有全局日志区域追加日志。
     *
     * @param level 日志级别
     * @param message 日志内容
     */
    private void log(String level, String message) {
        String formattedMessage = "[" + TimeUtils.getCurrentDateTime() + "]["
                + level + "] " + limitMessage(message);
        if ("ERROR".equals(level)) {
            standardError.accept(formattedMessage);
        } else {
            standardOutput.accept(formattedMessage);
        }

        long generation;
        long flushToken = 0;
        String line = formattedMessage + "\n";
        synchronized (pendingLogLock) {
            generation = logGeneration.get();
            pendingLogs.addLast(new PendingLog(generation, line));
            pendingLogCharacters += line.length();
            while ((pendingLogs.size() > MAX_PENDING_LOG_MESSAGES
                    || pendingLogCharacters > MAX_PENDING_LOG_CHARACTERS)
                    && pendingLogs.size() > 1) {
                PendingLog removed = pendingLogs.removeFirst();
                pendingLogCharacters -= removed.text().length();
                droppedLogCount++;
            }
            if (!logFlushScheduled) {
                logFlushScheduled = true;
                flushToken = ++logFlushToken;
            }
        }
        if (flushToken != 0) {
            scheduleLogFlush(flushToken, generation);
        }
    }

    private String limitMessage(String message) {
        String value = message == null ? "" : message;
        if (value.length() <= MAX_SINGLE_LOG_CHARACTERS) {
            return value;
        }
        int endIndex = MAX_SINGLE_LOG_CHARACTERS;
        if (Character.isHighSurrogate(value.charAt(endIndex - 1))
                && Character.isLowSurrogate(value.charAt(endIndex))) {
            endIndex--;
        }
        return value.substring(0, endIndex)
                + "\n[日志过长，已截断；原始字符数: " + value.length() + "]";
    }

    private void scheduleLogFlush(long flushToken, long generation) {
        if (!dispatchToFx(() -> flushPendingLogs(flushToken, generation))) {
            synchronized (pendingLogLock) {
                if (flushToken == logFlushToken) {
                    pendingLogs.clear();
                    pendingLogCharacters = 0;
                    droppedLogCount = 0;
                    logFlushScheduled = false;
                }
            }
        }
    }

    private void flushPendingLogs(long flushToken, long generation) {
        StringBuilder batch = new StringBuilder();
        long dropped;
        synchronized (pendingLogLock) {
            if (flushToken != logFlushToken || generation != logGeneration.get()) {
                return;
            }
            dropped = droppedLogCount;
            droppedLogCount = 0;
            int count = 0;
            while (count < LOG_BATCH_SIZE) {
                PendingLog pendingLog = pendingLogs.pollFirst();
                if (pendingLog == null) {
                    break;
                }
                pendingLogCharacters -= pendingLog.text().length();
                if (pendingLog.generation() == generation) {
                    batch.append(pendingLog.text());
                }
                count++;
            }
        }

        if (dropped > 0) {
            batch.insert(0, "[" + TimeUtils.getCurrentDateTime()
                    + "][WARN] 高负载期间已省略 " + dropped + " 条系统日志\n");
        }
        if (batch.length() > 0 && generation == logGeneration.get()) {
            synchronized (this) {
                cleanupReleasedAreas();
                for (WeakReference<TextArea> areaRef : globalLogAreas) {
                    safeAppend(areaRef.get(), batch.toString());
                }
            }
        }

        long nextFlushToken = 0;
        synchronized (pendingLogLock) {
            if (flushToken != logFlushToken || generation != logGeneration.get()) {
                return;
            }
            if (pendingLogs.isEmpty()) {
                logFlushScheduled = false;
            } else {
                nextFlushToken = ++logFlushToken;
            }
        }
        if (nextFlushToken != 0) {
            scheduleLogFlush(nextFlushToken, generation);
        }
    }

    /**
     * 清理已经被释放的日志区域引用。
     */
    private synchronized void cleanupReleasedAreas() {
        Iterator<WeakReference<TextArea>> iterator = globalLogAreas.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().get() == null) {
                iterator.remove();
            }
        }
    }

    /**
     * 安全追加日志到单个文本区域。
     *
     * @param area 日志文本区域
     * @param text 已格式化的批量日志内容
     */
    private void safeAppend(TextArea area, String text) {
        if (area != null && area.getScene() != null) {
            area.appendText(text);
            LogTextTrimmer.trimToMaxLines(area, MAX_LOG_LINES, 100);
            LogTextTrimmer.trimToMaxCharacters(
                    area, MAX_LOG_CHARACTERS, LOG_TRIM_TARGET_CHARACTERS);
        }
    }

    /**
     * 清空所有已注册的全局日志区域。
     */
    public void clearAll() {
        clearLogs();
    }

    private void clearRegisteredAreas() {
        cleanupReleasedAreas();
        for (WeakReference<TextArea> areaRef : globalLogAreas) {
            TextArea area = areaRef.get();
            if (area != null) {
                area.clear();
            }
        }
    }

    /**
     * 仅清空全局日志区域内容（不解除绑定）。
     */
    public void clearGlobalLogs() {
        clearLogs();
    }

    private void clearLogs() {
        Runnable clear = () -> {
            synchronized (this) {
                clearRegisteredAreas();
            }
        };
        synchronized (pendingLogLock) {
            logGeneration.incrementAndGet();
            pendingLogs.clear();
            pendingLogCharacters = 0;
            droppedLogCount = 0;
            logFlushScheduled = false;
            logFlushToken++;
            if (!fxThreadChecker.getAsBoolean()) {
                dispatchToFx(clear);
                return;
            }
        }
        clear.run();
    }

    private boolean dispatchToFx(Runnable task) {
        try {
            fxDispatcher.accept(task);
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private record PendingLog(long generation, String text) {
    }
}
