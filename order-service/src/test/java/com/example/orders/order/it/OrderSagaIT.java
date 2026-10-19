package com.example.orders.order.it;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.LineItem;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.event.OrderEventCodec;
import com.example.orders.common.testsupport.AwsTestClients;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Drives the order state machine by putting saga events on order-events-queue by hand. */
class OrderSagaIT extends OrderServiceIntegrationTest {

    private final OrderEventCodec codec = new OrderEventCodec();

    @Test
    void paymentCompletedBeforeInventoryReservedStillEndsConfirmed() {
        UUID orderId = createOrder();

        send(OrderEvent.paymentCompleted(orderId, new BigDecimal("10.00"), "tx-early", Instant.now()));
        await().atMost(Duration.ofSeconds(15)).until(() -> "COMPLETED".equals(getOrder(orderId).get("paymentStatus")));
        assertThat(getOrder(orderId).get("status")).isEqualTo("PENDING");

        send(reserved(orderId));
        awaitStatus(orderId, "CONFIRMED");
    }

    @Test
    void duplicateDeliveriesAreAppliedOnce() {
        UUID orderId = createOrder();
        OrderEvent reserved = reserved(orderId);
        OrderEvent paid = OrderEvent.paymentCompleted(orderId, new BigDecimal("10.00"), "tx-1", Instant.now());

        for (int i = 0; i < 3; i++) {
            send(reserved);
            send(paid);
        }

        awaitStatus(orderId, "CONFIRMED");
        // Let any remaining duplicates drain, then make sure nothing regressed.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .until(() -> AwsTestClients.approximateMessages(QueueNames.ORDER_EVENTS) == 0);
        assertThat(getOrder(orderId).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void paymentFailureFailsTheOrderAndEmitsExactlyOneInventoryRelease() {
        UUID orderId = createOrder();
        send(reserved(orderId));
        awaitStatus(orderId, "INVENTORY_RESERVED");

        OrderEvent failed = OrderEvent.paymentFailed(orderId, new BigDecimal("10.00"), "card declined", Instant.now());
        send(failed);
        send(failed);

        awaitStatus(orderId, "FAILED");
        assertThat((String) getOrder(orderId).get("failureReason")).contains("card declined");
        await().atMost(Duration.ofSeconds(10)).until(() -> AwsTestClients.approximateMessages(QueueNames.ORDER_EVENTS) == 0);
        Integer releases = jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ? and event_type = 'INVENTORY_RELEASE'",
                Integer.class, orderId);
        assertThat(releases).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(AwsTestClients.peek(QueueNames.INVENTORY).stream().map(m -> codec.decode(m.body())))
                        .anySatisfy(e -> {
                            assertThat(e.type()).isEqualTo(EventType.INVENTORY_RELEASE);
                            assertThat(e.orderId()).isEqualTo(orderId);
                        }));
    }

    @Test
    void inventoryFailureFailsTheOrderWithoutCompensation() {
        UUID orderId = createOrder();

        send(OrderEvent.inventoryFailed(orderId, "insufficient stock for SKU-001", Instant.now()));

        awaitStatus(orderId, "FAILED");
        assertThat(getOrder(orderId).get("inventoryStatus")).isEqualTo("FAILED");
        Integer releases = jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ? and event_type = 'INVENTORY_RELEASE'",
                Integer.class, orderId);
        assertThat(releases).isZero();
    }

    @Test
    void stuckOrderCanBeResumedByAnOperator() {
        UUID orderId = createOrder();

        ResponseEntity<Map> resumed = http.postForEntity("/admin/orders/" + orderId + "/resume", null, Map.class);

        assertThat(resumed.getStatusCode().value()).isEqualTo(202);
        Integer created = jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ? and event_type = 'ORDER_CREATED'",
                Integer.class, orderId);
        assertThat(created).isEqualTo(2);

        send(OrderEvent.inventoryFailed(orderId, "out of stock", Instant.now()));
        awaitStatus(orderId, "FAILED");
        assertThat(http.postForEntity("/admin/orders/" + orderId + "/resume", null, Map.class).getStatusCode().value())
                .isEqualTo(409);
    }

    private UUID createOrder() {
        ResponseEntity<Map> response = postOrder("saga-" + UUID.randomUUID(), orderJson("c", "SKU-001", 1, "10.00"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString((String) response.getBody().get("orderId"));
    }

    private static OrderEvent reserved(UUID orderId) {
        return OrderEvent.inventoryReserved(orderId, "c", List.of(new LineItem("SKU-001", 1, new BigDecimal("10.00"))),
                new BigDecimal("10.00"), Instant.now());
    }

    private void send(OrderEvent event) {
        AwsTestClients.send(QueueNames.ORDER_EVENTS, codec.encode(event));
    }

    private void awaitStatus(UUID orderId, String status) {
        await().atMost(Duration.ofSeconds(20)).until(() -> status.equals(getOrder(orderId).get("status")));
    }
}
