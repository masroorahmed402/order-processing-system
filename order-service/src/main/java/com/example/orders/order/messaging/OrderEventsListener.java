package com.example.orders.order.messaging;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.OrderEventCodec;
import com.example.orders.common.messaging.ConsumerMetrics;
import com.example.orders.order.application.OrderSagaService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.stereotype.Component;

/** Thin adapter: decode, then hand over to {@link OrderSagaService}. Failures go to the error handler. */
@Component
class OrderEventsListener {

    private final OrderSagaService saga;
    private final OrderEventCodec codec;
    private final ConsumerMetrics metrics;

    OrderEventsListener(OrderSagaService saga, OrderEventCodec codec, ConsumerMetrics metrics) {
        this.saga = saga;
        this.codec = codec;
        this.metrics = metrics;
    }

    @SqsListener(QueueNames.ORDER_EVENTS)
    void onMessage(String body) {
        metrics.timed(QueueNames.ORDER_EVENTS, () -> saga.handle(codec.decode(body)));
    }
}
