package com.example.orders.inventory.messaging;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.OrderEventCodec;
import com.example.orders.common.messaging.ConsumerMetrics;
import com.example.orders.inventory.application.InventoryService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.stereotype.Component;

/** Thin adapter: decode, then hand over to {@link InventoryService}. */
@Component
class InventoryListener {

    private final InventoryService inventory;
    private final OrderEventCodec codec;
    private final ConsumerMetrics metrics;

    InventoryListener(InventoryService inventory, OrderEventCodec codec, ConsumerMetrics metrics) {
        this.inventory = inventory;
        this.codec = codec;
        this.metrics = metrics;
    }

    @SqsListener(QueueNames.INVENTORY)
    void onMessage(String body) {
        metrics.timed(QueueNames.INVENTORY, () -> inventory.handle(codec.decode(body)));
    }
}
