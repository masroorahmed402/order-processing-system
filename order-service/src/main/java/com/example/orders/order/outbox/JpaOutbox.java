package com.example.orders.order.outbox;

import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.event.OrderEventCodec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaOutbox implements Outbox {

    private final OutboxEventRepository repository;
    private final OrderEventCodec codec;

    JpaOutbox(OutboxEventRepository repository, OrderEventCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String destinationQueue, OrderEvent event) {
        repository.save(new OutboxEvent(event.eventId(), event.orderId(), event.type().name(), destinationQueue,
                codec.encode(event), event.occurredAt()));
    }
}
