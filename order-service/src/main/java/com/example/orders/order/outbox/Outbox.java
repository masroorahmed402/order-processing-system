package com.example.orders.order.outbox;

import com.example.orders.common.event.OrderEvent;

/**
 * Transactional outbox port. {@link #enqueue} must be called inside the transaction that changes
 * the order, so the state change and the event are committed (or rolled back) together.
 */
public interface Outbox {

    void enqueue(String destinationQueue, OrderEvent event);
}
