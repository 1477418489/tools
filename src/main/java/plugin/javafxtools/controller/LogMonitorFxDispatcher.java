package plugin.javafxtools.controller;

import plugin.javafxtools.model.LogMonitorMatch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Applies backpressure between the log poller and the JavaFX application thread.
 */
final class LogMonitorFxDispatcher implements AutoCloseable {
    private static final int DEFAULT_MAX_PENDING_MATCHES = 500;
    private static final int DEFAULT_MAX_PENDING_CHARACTERS = 1_000_000;
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int DEFAULT_BATCH_CHARACTERS = 250_000;

    @FunctionalInterface
    interface BatchConsumer {
        void accept(List<LogMonitorMatch> matches, long droppedCount);
    }

    private final Object lock = new Object();
    private final Deque<PendingMatch> pendingMatches = new ArrayDeque<>();
    private final Consumer<Runnable> fxDispatcher;
    private final BatchConsumer batchConsumer;
    private final int maxPendingMatches;
    private final int maxPendingCharacters;
    private final int batchSize;
    private final int batchCharacters;

    private long pendingCharacters;
    private long droppedCount;
    private long flushToken;
    private boolean flushScheduled;
    private boolean closed;

    LogMonitorFxDispatcher(Consumer<Runnable> fxDispatcher, BatchConsumer batchConsumer) {
        this(fxDispatcher, batchConsumer,
                DEFAULT_MAX_PENDING_MATCHES, DEFAULT_MAX_PENDING_CHARACTERS,
                DEFAULT_BATCH_SIZE, DEFAULT_BATCH_CHARACTERS);
    }

    LogMonitorFxDispatcher(Consumer<Runnable> fxDispatcher, BatchConsumer batchConsumer,
                           int maxPendingMatches, int maxPendingCharacters,
                           int batchSize, int batchCharacters) {
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher");
        this.batchConsumer = Objects.requireNonNull(batchConsumer, "batchConsumer");
        if (maxPendingMatches <= 0 || maxPendingCharacters <= 0
                || batchSize <= 0 || batchCharacters <= 0) {
            throw new IllegalArgumentException("All dispatcher limits must be positive");
        }
        this.maxPendingMatches = maxPendingMatches;
        this.maxPendingCharacters = maxPendingCharacters;
        this.batchSize = batchSize;
        this.batchCharacters = batchCharacters;
    }

    void submit(List<LogMonitorMatch> matches) {
        List<LogMonitorMatch> snapshot = List.copyOf(Objects.requireNonNull(matches, "matches"));
        if (snapshot.isEmpty()) {
            return;
        }

        long token = 0;
        synchronized (lock) {
            if (closed) {
                return;
            }
            for (LogMonitorMatch match : snapshot) {
                int characters = estimatedCharacters(match);
                pendingMatches.addLast(new PendingMatch(match, characters));
                pendingCharacters += characters;
            }
            trimOverflow();
            if (!flushScheduled) {
                flushScheduled = true;
                token = ++flushToken;
            }
        }
        if (token != 0) {
            scheduleFlush(token);
        }
    }

    void clear() {
        synchronized (lock) {
            pendingMatches.clear();
            pendingCharacters = 0;
            droppedCount = 0;
            flushScheduled = false;
            flushToken++;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pendingMatches.clear();
            pendingCharacters = 0;
            droppedCount = 0;
            flushScheduled = false;
            flushToken++;
        }
    }

    private void trimOverflow() {
        while ((pendingMatches.size() > maxPendingMatches
                || pendingCharacters > maxPendingCharacters)
                && pendingMatches.size() > 1) {
            PendingMatch removed = pendingMatches.removeFirst();
            pendingCharacters -= removed.characters();
            droppedCount++;
        }
    }

    private void scheduleFlush(long token) {
        try {
            fxDispatcher.accept(() -> flush(token));
        } catch (IllegalStateException exception) {
            synchronized (lock) {
                if (token == flushToken) {
                    pendingMatches.clear();
                    pendingCharacters = 0;
                    droppedCount = 0;
                    flushScheduled = false;
                }
            }
        }
    }

    private void flush(long token) {
        List<LogMonitorMatch> batch = new ArrayList<>(batchSize);
        long dropped;
        synchronized (lock) {
            if (closed || token != flushToken) {
                return;
            }
            long characters = 0;
            while (batch.size() < batchSize) {
                PendingMatch pending = pendingMatches.peekFirst();
                if (pending == null) {
                    break;
                }
                if (!batch.isEmpty() && characters + pending.characters() > batchCharacters) {
                    break;
                }
                pendingMatches.removeFirst();
                pendingCharacters -= pending.characters();
                characters += pending.characters();
                batch.add(pending.match());
            }
            dropped = droppedCount;
            droppedCount = 0;
        }

        RuntimeException failure = null;
        try {
            if (!batch.isEmpty() || dropped > 0) {
                batchConsumer.accept(List.copyOf(batch), dropped);
            }
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            scheduleNext(token);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void scheduleNext(long token) {
        long nextToken = 0;
        synchronized (lock) {
            if (closed || token != flushToken) {
                return;
            }
            if (pendingMatches.isEmpty()) {
                flushScheduled = false;
            } else {
                nextToken = ++flushToken;
            }
        }
        if (nextToken != 0) {
            scheduleFlush(nextToken);
        }
    }

    private static int estimatedCharacters(LogMonitorMatch match) {
        Objects.requireNonNull(match, "match");
        long characters = length(match.ruleId())
                + length(match.ruleName())
                + length(match.expression())
                + length(match.line())
                + (match.logFile() == null ? 0 : match.logFile().toString().length());
        return (int) Math.min(Integer.MAX_VALUE, characters);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private record PendingMatch(LogMonitorMatch match, int characters) {
    }
}
