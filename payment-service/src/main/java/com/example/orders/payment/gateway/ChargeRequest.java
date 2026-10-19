package com.example.orders.payment.gateway;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record ChargeRequest(String idempotencyKey, UUID orderId, BigDecimal amount) {

    public ChargeRequest {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(amount, "amount");
    }
}
