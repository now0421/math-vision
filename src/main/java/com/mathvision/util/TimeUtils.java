package com.mathvision.util;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared time utilities.
 */
public final class TimeUtils {

    private TimeUtils() {}

    /**
     * Returns the elapsed time in seconds since the given start instant.
     */
    public static double secondsSince(Instant start) {
        return Duration.between(start, Instant.now()).toNanos() / 1_000_000_000.0;
    }
}
