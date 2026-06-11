package com.mathvision.service;

import com.mathvision.config.ModelConfig;
import com.mathvision.util.ConcurrencyUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared timeout and retry policy for AI provider requests.
 */
public final class AiRetryPolicy {

    private static final long RETRY_BASE_DELAY_MILLIS = 1_000L;
    private static final long RETRY_MAX_DELAY_MILLIS = 4_000L;
    private static final double RATE_LIMIT_JITTER_RATIO = 0.25;

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

    public static int rateLimitRetries(ModelConfig modelConfig) {
        return Math.max(modelConfig.getRateLimitRetries(), 0);
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

    public static long rateLimitDelayMillis(ModelConfig modelConfig, int attempt) {
        return rateLimitDelayMillis(modelConfig, attempt, Optional.empty());
    }

    public static long rateLimitDelayMillis(ModelConfig modelConfig,
                                            int attempt,
                                            Optional<String> retryAfterHeader) {
        return rateLimitDelayMillis(modelConfig, attempt, retryAfterHeader, ThreadLocalRandom.current());
    }

    static long rateLimitDelayMillis(ModelConfig modelConfig,
                                     int attempt,
                                     Optional<String> retryAfterHeader,
                                     Random random) {
        Optional<Long> retryAfterMillis = parseRetryAfterMillis(retryAfterHeader);
        if (retryAfterMillis.isPresent()) {
            return clampRateLimitDelay(modelConfig, retryAfterMillis.get());
        }

        long baseDelay = Math.max(modelConfig.getRateLimitBaseDelayMillis(), 1L);
        long maxDelay = Math.max(modelConfig.getRateLimitMaxDelayMillis(), baseDelay);
        long exponential = multiplyByPowerOfTwoSaturated(baseDelay, attempt);
        long capped = Math.min(exponential, maxDelay);
        long jitterWindow = Math.max(1L, Math.round(capped * RATE_LIMIT_JITTER_RATIO));
        long jitter = random.nextInt((int) Math.min(jitterWindow, Integer.MAX_VALUE)) + 1L;
        return Math.min(capped + jitter, maxDelay);
    }

    public static Optional<String> retryAfterHeader(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.firstValue("Retry-After")
                .or(() -> headers.firstValue("retry-after"));
    }

    static Optional<Long> parseRetryAfterMillis(Optional<String> retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.isEmpty()) {
            return Optional.empty();
        }
        String value = retryAfterHeader.get();
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return Optional.of(Math.max(seconds, 0L) * 1_000L);
        } catch (NumberFormatException ignored) {
            // Continue with HTTP-date parsing below.
        }
        try {
            ZonedDateTime retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            long millis = Duration.between(ZonedDateTime.now(retryAt.getZone()), retryAt).toMillis();
            return Optional.of(Math.max(millis, 0L));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static long clampRateLimitDelay(ModelConfig modelConfig, long delayMillis) {
        long baseDelay = Math.max(modelConfig.getRateLimitBaseDelayMillis(), 1L);
        long maxDelay = Math.max(modelConfig.getRateLimitMaxDelayMillis(), baseDelay);
        return Math.min(Math.max(delayMillis, baseDelay), maxDelay);
    }

    private static long multiplyByPowerOfTwoSaturated(long value, int exponent) {
        long result = value;
        int safeExponent = Math.max(exponent, 0);
        for (int i = 0; i < safeExponent; i++) {
            if (result > Long.MAX_VALUE / 2L) {
                return Long.MAX_VALUE;
            }
            result *= 2L;
        }
        return result;
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

    public static boolean isRateLimitFailure(Throwable error) {
        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
        if (cause == null) {
            return false;
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("429")
                || normalized.contains("rate limit")
                || normalized.contains("rate_limit")
                || normalized.contains("too many requests")
                || normalized.contains("resource exhausted")
                || normalized.contains("quota exceeded");
    }

    public static boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500;
    }

    public static boolean isRateLimitStatusCode(int statusCode) {
        return statusCode == 429;
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
