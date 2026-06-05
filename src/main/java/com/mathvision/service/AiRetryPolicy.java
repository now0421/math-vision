package com.mathvision.service;

import com.mathvision.config.ModelConfig;
import com.mathvision.util.ConcurrencyUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

/**
 * Shared timeout and retry policy for AI provider requests.
 */
public final class AiRetryPolicy {

    private static final long RETRY_BASE_DELAY_MILLIS = 1_000L;
    private static final long RETRY_MAX_DELAY_MILLIS = 4_000L;

    private AiRetryPolicy() {}

    public static int initialTimeoutSeconds(ModelConfig modelConfig) {
        return Math.max(modelConfig.getRequestTimeoutSeconds(), 1);
    }

    public static int timeoutRetryAttempts(ModelConfig modelConfig) {
        return Math.max(modelConfig.getTimeoutRetryAttempts(), 0);
    }

    public static int transientFailureRetries(ModelConfig modelConfig) {
        return Math.max(modelConfig.getTransientFailureRetries(), 0);
    }

    public static int nextTimeoutSeconds(ModelConfig modelConfig, int currentTimeoutSeconds) {
        double multiplier = modelConfig.getTimeoutRetryMultiplier() > 1.0
                ? modelConfig.getTimeoutRetryMultiplier()
                : 2.0;
        long next = Math.max(currentTimeoutSeconds + 1L,
                Math.round(currentTimeoutSeconds * multiplier));
        int max = maxTimeoutSeconds(modelConfig);
        return (int) Math.min(next, max);
    }

    public static int maxTimeoutSeconds(ModelConfig modelConfig) {
        int initial = initialTimeoutSeconds(modelConfig);
        int configured = modelConfig.getMaxRequestTimeoutSeconds();
        return configured >= initial ? configured : initial;
    }

    public static long retryDelayMillis(int attempt) {
        long delay = RETRY_BASE_DELAY_MILLIS * (1L << Math.min(attempt, 30));
        return Math.min(delay, RETRY_MAX_DELAY_MILLIS);
    }

    public static boolean isTimeoutFailure(Throwable error) {
        Throwable current = ConcurrencyUtils.unwrapCompletionException(error);
        while (current != null) {
            if (current instanceof HttpTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean isRetryableTransportFailure(Throwable error) {
        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
        if (cause instanceof IOException) {
            return true;
        }
        String message = cause != null ? cause.getMessage() : null;
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("rst_stream")
                || normalized.contains("goaway")
                || normalized.contains("connection reset")
                || normalized.contains("stream was reset")
                || normalized.contains("temporarily unavailable");
    }

    public static boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500;
    }

    public static void logTimeoutRetry(Logger log,
                                String clientName,
                                int currentTimeoutSeconds,
                                int timeoutAttempt,
                                int maxTimeoutAttempts,
                                int nextTimeoutSeconds) {
        log.warn("{} request timed out after {}s (timeout retry {}/{}), retrying with timeout {}s",
                clientName,
                currentTimeoutSeconds,
                timeoutAttempt + 1,
                maxTimeoutAttempts,
                nextTimeoutSeconds);
    }

    public static void logTimeoutExhausted(Logger log,
                                    String clientName,
                                    int currentTimeoutSeconds) {
        log.warn("{} request timed out after {}s; timeout retries exhausted",
                clientName,
                currentTimeoutSeconds);
    }
}
