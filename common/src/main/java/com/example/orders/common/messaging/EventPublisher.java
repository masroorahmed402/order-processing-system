package com.example.orders.common.messaging;

import com.example.orders.common.event.OrderEvent;

import java.util.List;

/** Sends events to a queue. Implementations must throw if a message could not be sent. */
public interface EventPublisher {

    void publish(String queue, OrderEvent event);

    /** Sends already-serialized event bodies (used by the transactional outbox). */
    void publishRaw(String queue, List<String> bodies);
}
