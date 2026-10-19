package com.example.orders.payment.messaging;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.OrderEventCodec;
import com.example.orders.common.messaging.ConsumerMetrics;
import com.example.orders.payment.application.PaymentService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.stereotype.Component;

/** Thin adapter: decode, then hand over to {@link PaymentService}. */
@Component
class PaymentListener {

    private final PaymentService payments;
    private final OrderEventCodec codec;
    private final ConsumerMetrics metrics;

    PaymentListener(PaymentService payments, OrderEventCodec codec, ConsumerMetrics metrics) {
        this.payments = payments;
        this.codec = codec;
        this.metrics = metrics;
    }

    @SqsListener(QueueNames.PAYMENT)
    void onMessage(String body) {
        metrics.timed(QueueNames.PAYMENT, () -> payments.handle(codec.decode(body)));
    }
}
