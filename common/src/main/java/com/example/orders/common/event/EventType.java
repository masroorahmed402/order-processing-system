package com.example.orders.common.event;

/** Every message exchanged between the services. */
public enum EventType {
    /** order-service → inventory: reserve stock for a new order. */
    ORDER_CREATED,
    /** inventory → payment and order-service: stock is held for the order. */
    INVENTORY_RESERVED,
    /** inventory → order-service: stock could not be reserved; the order fails. */
    INVENTORY_FAILED,
    /** payment → order-service: the order was charged. */
    PAYMENT_COMPLETED,
    /** payment → order-service: the charge was declined; triggers compensation. */
    PAYMENT_FAILED,
    /** order-service → inventory: compensating action, give the reserved stock back. */
    INVENTORY_RELEASE
}
