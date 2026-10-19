package com.example.orders.payment.gateway;

public sealed interface ChargeResult {

    record Approved(String transactionId) implements ChargeResult {
    }

    record Declined(String reason) implements ChargeResult {
    }
}
