package com.example.orders.inventory.domain;

import java.util.UUID;

/** Concurrent transactions kept conflicting on the same items. Transient: SQS will retry with backoff. */
public class StockContentionException extends RuntimeException {

    public StockContentionException(UUID orderId, int attempts) {
        super("Gave up reserving/releasing stock for order " + orderId + " after " + attempts + " conflicting attempts");
    }
}
