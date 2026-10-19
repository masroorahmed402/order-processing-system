package com.example.orders.payment.domain;

import com.example.orders.payment.gateway.ChargeResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Payment records keyed by order id. Every write is conditional. */
public interface PaymentRepository {

    Optional<PaymentRecord> find(UUID orderId);

    /** Creates the CHARGING record; false if any record already exists for the order. */
    boolean claim(UUID orderId, BigDecimal amount, String claimToken, Instant now);

    /** Replaces a stale claim's token with ours; false if the claim changed meanwhile. */
    boolean takeOver(UUID orderId, String staleToken, String newToken, Instant now);

    /** Stores the gateway result if we still own the claim. */
    Optional<PaymentRecord> complete(UUID orderId, String claimToken, ChargeResult result, Instant now);

    /** Deletes our claim after a transient gateway failure so the retry can claim again. */
    void releaseClaim(UUID orderId, String claimToken);
}
