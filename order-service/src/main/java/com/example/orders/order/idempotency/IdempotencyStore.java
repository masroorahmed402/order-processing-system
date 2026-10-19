package com.example.orders.order.idempotency;

import java.time.Instant;
import java.util.UUID;

/** Storage for API idempotency keys. Implemented with DynamoDB conditional writes. */
public interface IdempotencyStore {

    /**
     * Atomically claims {@code key} for a request with the given fingerprint and the order id it
     * will create. At most one caller ever gets {@link ClaimResult.Claimed} for a key.
     */
    ClaimResult claim(String key, String fingerprint, UUID orderId, Instant now);

    void markCompleted(String key, UUID orderId, Instant now);
}
