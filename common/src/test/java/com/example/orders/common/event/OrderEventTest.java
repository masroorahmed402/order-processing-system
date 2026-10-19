package com.example.orders.common.event;

import com.example.orders.common.QueueNames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEventTest {

    private final UUID orderId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @Test
    void factoriesProduceValidEventsWithFreshIds() {
        List<LineItem> items = List.of(new LineItem("SKU-001", 1, BigDecimal.TEN));
        OrderEvent a = OrderEvent.orderCreated(orderId, "c", items, BigDecimal.TEN, now);
        OrderEvent b = OrderEvent.inventoryReserved(orderId, "c", items, BigDecimal.TEN, now);

        assertThat(a.type()).isEqualTo(EventType.ORDER_CREATED);
        assertThat(b.type()).isEqualTo(EventType.INVENTORY_RESERVED);
        assertThat(a.eventId()).isNotEqualTo(b.eventId());
        assertThat(OrderEvent.inventoryFailed(orderId, "out of stock", now).reason()).isEqualTo("out of stock");
        assertThat(OrderEvent.paymentFailed(orderId, BigDecimal.ONE, "declined", now).type()).isEqualTo(EventType.PAYMENT_FAILED);
        assertThat(OrderEvent.inventoryRelease(orderId, "payment failed", now).items()).isEmpty();
    }

    @Test
    void itemsAreDefensivelyCopied() {
        List<LineItem> items = new ArrayList<>(List.of(new LineItem("SKU-001", 1, BigDecimal.TEN)));
        OrderEvent event = OrderEvent.orderCreated(orderId, "c", items, BigDecimal.TEN, now);
        items.clear();

        assertThat(event.items()).hasSize(1);
        assertThatThrownBy(() -> event.items().add(new LineItem("x", 1, BigDecimal.ONE)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void typeSpecificFieldsAreRequired() {
        assertThatThrownBy(() -> OrderEvent.inventoryFailed(orderId, " ", now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderEvent.paymentCompleted(orderId, BigDecimal.ONE, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderEvent.orderCreated(orderId, "c", List.of(), BigDecimal.ONE, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderEvent.inventoryReserved(orderId, "c",
                List.of(new LineItem("SKU-001", 1, BigDecimal.ONE)), null, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lineItemValidationAndTotal() {
        assertThat(new LineItem("SKU-001", 3, new BigDecimal("2.50")).lineTotal()).isEqualByComparingTo("7.50");
        assertThatThrownBy(() -> new LineItem(" ", 1, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineItem("SKU", -1, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineItem("SKU", 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LineItem("SKU", 1, new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadLetterQueueNaming() {
        assertThat(QueueNames.deadLetterQueueOf(QueueNames.PAYMENT)).isEqualTo("payment-queue-dlq");
    }
}
