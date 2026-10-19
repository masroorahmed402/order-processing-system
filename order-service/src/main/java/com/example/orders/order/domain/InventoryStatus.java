package com.example.orders.order.domain;

/** What the order knows about its stock reservation. */
public enum InventoryStatus {
    PENDING,
    RESERVED,
    FAILED
}
