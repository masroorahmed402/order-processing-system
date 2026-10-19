package com.example.orders.payment.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** One row per order in the {@code payments} table. */
public record PaymentRecord(UUID orderId, PaymentStatus status, BigDecimal amount, String transactionId,
                            String reason, String claimToken, Instant claimedAt, Instant updatedAt) {

    public boolean isFinal() {
        return status.isFinal();
    }

    /** A CHARGING claim this old belongs to a consumer that died mid-charge. */
    public boolean isStaleClaim(Instant now, Duration staleAfter) {
        return status == PaymentStatus.CHARGING && claimedAt.plus(staleAfter).isBefore(now);
    }
}
