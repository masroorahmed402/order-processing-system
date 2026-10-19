package com.example.orders.payment.gateway;

/** The processor is temporarily unavailable. Retryable; nothing was charged. */
public class TransientPaymentException extends RuntimeException {

    public TransientPaymentException(String message) {
        super(message);
    }
}
