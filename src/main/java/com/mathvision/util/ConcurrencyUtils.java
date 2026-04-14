package com.mathvision.util;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Shared helpers for async scheduling and CompletionException unwrapping.
 */
public final class ConcurrencyUtils {

    private ConcurrencyUtils() {}

    public static Throwable unwrapCompletionException(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static final class AsyncLimiter {
        private final int maxInFlight;
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private int inFlight = 0;

        public AsyncLimiter(int maxInFlight) {
            this.maxInFlight = Math.max(1, maxInFlight);
        }

        public <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> taskFactory) {
            CompletableFuture<T> result = new CompletableFuture<>();
            Runnable startTask = () -> runTask(taskFactory, result);

            boolean runNow;
            synchronized (this) {
                if (inFlight < maxInFlight) {
                    inFlight++;
                    runNow = true;
                } else {
                    queue.addLast(startTask);
                    runNow = false;
                }
            }

            if (runNow) {
                startTask.run();
            }

            return result;
        }

        private <T> void runTask(Supplier<CompletableFuture<T>> taskFactory, CompletableFuture<T> result) {
            CompletableFuture<T> taskFuture;
            try {
                taskFuture = taskFactory.get();
            } catch (Throwable error) {
                releaseSlot();
                result.completeExceptionally(error);
                return;
            }

            taskFuture.whenComplete((value, error) -> {
                try {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(value);
                    }
                } finally {
                    releaseSlot();
                }
            });
        }

        private void releaseSlot() {
            Runnable nextTask = null;
            synchronized (this) {
                inFlight--;
                if (!queue.isEmpty()) {
                    nextTask = queue.removeFirst();
                    inFlight++;
                }
            }

            if (nextTask != null) {
                nextTask.run();
            }
        }
    }
}
