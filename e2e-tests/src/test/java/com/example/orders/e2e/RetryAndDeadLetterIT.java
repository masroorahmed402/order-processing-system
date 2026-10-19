package com.example.orders.e2e;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.OrderEventCodec;
import com.example.orders.common.messaging.DeadLetterPublisher;
import com.example.orders.common.messaging.ExponentialBackoff;
import com.example.orders.common.testsupport.AwsTestClients;
import com.example.orders.payment.gateway.PaymentGatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Proves transient failures are retried with exponential backoff and end up in the DLQ. */
class RetryAndDeadLetterIT extends E2ESupport {

    private static final String PAYMENT_DLQ = QueueNames.deadLetterQueueOf(QueueNames.PAYMENT);
    private final OrderEventCodec codec = new OrderEventCodec();

    @AfterEach
    void restoreGateway() {
        env.payment.getBean(PaymentGatewayProperties.class).setTransientFailureRate(0.0);
    }

    @Test
    void transientPaymentFailuresBackOffExponentiallyThenLandInTheDlqAndCanBeRedriven() {
        PaymentGatewayProperties gatewayProperties = env.payment.getBean(PaymentGatewayProperties.class);
        ExponentialBackoff backoff = env.payment.getBean(ExponentialBackoff.class);
        gatewayProperties.setTransientFailureRate(1.0);
        String sku = freshSku(10);

        UUID orderId = placeOrderId(sku, 1, "10.00");

        // maxReceiveCount = 5: five attempts, then SQS moves the message to the DLQ.
        Message dead = await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500))
                .until(() -> findInDlq(orderId), Optional::isPresent).orElseThrow();
        List<Instant> attempts = gateway().attempts(orderId);
        assertThat(attempts).hasSize(5);

        List<Long> gapsMillis = new ArrayList<>();
        for (int i = 1; i < attempts.size(); i++) {
            gapsMillis.add(Duration.between(attempts.get(i - 1), attempts.get(i)).toMillis());
        }
        System.out.printf("Payment attempt gaps (ms): %s%n", gapsMillis);
        for (int i = 0; i < gapsMillis.size(); i++) {
            long expected = backoff.visibilityTimeoutSeconds(i + 1) * 1000L; // 1s, 2s, 4s, 8s
            assertThat(gapsMillis.get(i)).as("gap after attempt %d", i + 1)
                    .isGreaterThanOrEqualTo(expected - 250)
                    .isLessThan(expected + 3000);
            if (i > 0) {
                assertThat(gapsMillis.get(i)).isGreaterThan(gapsMillis.get(i - 1));
            }
        }
        assertThat(codec.decode(dead.body()).type()).isEqualTo(EventType.INVENTORY_RESERVED);
        assertThat(order(orderId).get("status")).isEqualTo("INVENTORY_RESERVED");
        assertThat(payment(orderId)).isNull();

        // Runbook: fix the cause, then redrive the DLQ message. The saga completes, charged once.
        gatewayProperties.setTransientFailureRate(0.0);
        AwsTestClients.send(QueueNames.PAYMENT, dead.body());
        AwsTestClients.sqs().deleteMessage(r -> r.queueUrl(AwsTestClients.queueUrl(PAYMENT_DLQ)).receiptHandle(dead.receiptHandle()));
        awaitStatus(orderId, "CONFIRMED");
        assertThat(simulatedGateway().chargeCount(orderId)).isEqualTo(1);
    }

    @Test
    void malformedMessagesGoStraightToTheDlqWithoutRetries() {
        String garbage = "{\"this is\": \"not an order event " + UUID.randomUUID() + "\"}";

        AwsTestClients.send(QueueNames.INVENTORY, garbage);

        String inventoryDlq = QueueNames.deadLetterQueueOf(QueueNames.INVENTORY);
        Message dead = await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300))
                .until(() -> AwsTestClients.peek(inventoryDlq).stream().filter(m -> m.body().equals(garbage)).findFirst(),
                        Optional::isPresent).orElseThrow();
        assertThat(dead.messageAttributes().get(DeadLetterPublisher.FAILURE_REASON_HEADER).stringValue())
                .contains("MalformedEventException");
        assertThat(dead.messageAttributes().get(DeadLetterPublisher.SOURCE_QUEUE_HEADER).stringValue())
                .isEqualTo(QueueNames.INVENTORY);
        AwsTestClients.sqs().deleteMessage(r -> r.queueUrl(AwsTestClients.queueUrl(inventoryDlq)).receiptHandle(dead.receiptHandle()));
    }

    private Optional<Message> findInDlq(UUID orderId) {
        return AwsTestClients.peek(PAYMENT_DLQ).stream()
                .filter(m -> m.body().contains(orderId.toString()))
                .findFirst();
    }
}
