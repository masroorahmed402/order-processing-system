package com.example.orders.order.idempotency;

/** The key was already used for a request with a different body (HTTP 422). */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
