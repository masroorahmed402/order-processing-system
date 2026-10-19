package com.example.orders.order.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * What the idempotency table remembers about a key: which request it was used for
 * (fingerprint) and which order id that request owns.
 */
public record IdempotencyRecord(String key, String fingerprint, UUID orderId, Status status, Instant createdAt) {

    public enum Status {
        /** Claimed; the order may or may not have been written yet. */
        IN_PROGRESS,
        /** The order was written; replays simply return it. */
        COMPLETED
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    /** An IN_PROGRESS claim this old belongs to a request that died before writing the order. */
    public boolean isStale(Instant now, Duration staleAfter) {
        return status == Status.IN_PROGRESS && createdAt.plus(staleAfter).isBefore(now);
    }
}
