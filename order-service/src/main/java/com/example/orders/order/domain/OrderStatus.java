package com.example.orders.order.domain;

/** Customer-visible order status. CONFIRMED and FAILED are terminal. */
public enum OrderStatus {
    PENDING,
    INVENTORY_RESERVED,
    CONFIRMED,
    FAILED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == FAILED;
    }
}
