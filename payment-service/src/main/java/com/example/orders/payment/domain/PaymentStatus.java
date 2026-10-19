package com.example.orders.payment.domain;

public enum PaymentStatus {
    /** Claimed by a consumer; the gateway call is in flight. */
    CHARGING,
    COMPLETED,
    DECLINED;

    public boolean isFinal() {
        return this != CHARGING;
    }
}
