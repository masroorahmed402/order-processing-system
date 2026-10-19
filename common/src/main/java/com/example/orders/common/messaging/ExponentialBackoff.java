package com.example.orders.common.messaging;

/**
 * Computes how long a failed message stays invisible before SQS delivers it again:
 * {@code min(baseSeconds * 2^(receiveCount - 1), maxSeconds)}.
 *
 * <p>With base 2s: attempt 1 fails → 2s, attempt 2 → 4s, then 8s, 16s, ... capped at
 * {@code maxSeconds} (300s by default, well under SQS's 12-hour visibility limit).
 */
public final class ExponentialBackoff {

    /** SQS rejects visibility timeouts above 12 hours. */
    public static final int SQS_MAX_VISIBILITY_SECONDS = 43_200;

    private final int baseSeconds;
    private final int maxSeconds;

    public ExponentialBackoff(int baseSeconds, int maxSeconds) {
        if (baseSeconds < 1) {
            throw new IllegalArgumentException("baseSeconds must be >= 1: " + baseSeconds);
        }
        if (maxSeconds < baseSeconds || maxSeconds > SQS_MAX_VISIBILITY_SECONDS) {
            throw new IllegalArgumentException(
                    "maxSeconds must be between baseSeconds and " + SQS_MAX_VISIBILITY_SECONDS + ": " + maxSeconds);
        }
        this.baseSeconds = baseSeconds;
        this.maxSeconds = maxSeconds;
    }

    /**
     * @param receiveCount SQS ApproximateReceiveCount of the delivery that just failed (1 = first attempt).
     *                     Values below 1 (header missing) are treated as 1.
     */
    public int visibilityTimeoutSeconds(int receiveCount) {
        int exponent = Math.max(receiveCount, 1) - 1;
        // Beyond 2^30 the multiplication would overflow; the cap is reached long before that anyway.
        if (exponent >= 30) {
            return maxSeconds;
        }
        long delay = (long) baseSeconds << exponent;
        return (int) Math.min(delay, maxSeconds);
    }

    public int baseSeconds() {
        return baseSeconds;
    }

    public int maxSeconds() {
        return maxSeconds;
    }
}
