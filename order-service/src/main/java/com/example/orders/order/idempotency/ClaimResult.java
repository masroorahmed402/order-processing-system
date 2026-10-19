package com.example.orders.order.idempotency;

/** Outcome of trying to claim an idempotency key. */
public sealed interface ClaimResult {

    /** This request owns the key and must create the order. */
    record Claimed() implements ClaimResult {
    }

    /** Another request already claimed the key. */
    record AlreadyClaimed(IdempotencyRecord existing) implements ClaimResult {
    }
}
