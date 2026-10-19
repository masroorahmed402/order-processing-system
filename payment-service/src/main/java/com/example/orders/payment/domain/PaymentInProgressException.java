package com.example.orders.payment.domain;

import java.util.UUID;

/** Another consumer is charging this order right now. Transient: retried with backoff. */
public class PaymentInProgressException extends RuntimeException {

    public PaymentInProgressException(UUID orderId) {
        super("Payment for order " + orderId + " is being processed by another consumer");
    }
}
