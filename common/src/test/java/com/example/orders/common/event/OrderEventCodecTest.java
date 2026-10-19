package com.example.orders.common.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEventCodecTest {

    private final OrderEventCodec codec = new OrderEventCodec();
    private final Instant now = Instant.parse("2026-10-19T10:15:30.123Z");

    @Test
    void roundTripsAnOrderCreatedEvent() {
        OrderEvent event = OrderEvent.orderCreated(UUID.randomUUID(), "customer-1",
                List.of(new LineItem("SKU-001", 2, new BigDecimal("19.99")), new LineItem("SKU-002", 1, new BigDecimal("5.00"))),
                new BigDecimal("44.98"), now);

        OrderEvent decoded = codec.decode(codec.encode(event));

        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.items()).hasSize(2);
        assertThat(decoded.amount()).isEqualByComparingTo("44.98");
    }

    @Test
    void encodesTimestampsAsIso8601AndOmitsNullFields() {
        OrderEvent event = OrderEvent.paymentCompleted(UUID.randomUUID(), new BigDecimal("10.00"), "tx-1", now);

        String json = codec.encode(event);

        assertThat(json).contains("\"occurredAt\":\"2026-10-19T10:15:30.123Z\"")
                .contains("\"type\":\"PAYMENT_COMPLETED\"")
                .doesNotContain("\"reason\"")
                .doesNotContain("\"customerId\"");
    }

    @Test
    void ignoresUnknownFieldsForForwardCompatibility() {
        UUID orderId = UUID.randomUUID();
        String json = """
                {"eventId":"%s","type":"INVENTORY_RELEASE","orderId":"%s","occurredAt":"2026-10-19T10:15:30Z",
                 "someFutureField":{"nested":true}}
                """.formatted(UUID.randomUUID(), orderId);

        assertThat(codec.decode(json).orderId()).isEqualTo(orderId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "null",
            "{not json",
            "[1,2,3]",
            "{\"type\":\"ORDER_CREATED\"}",
            "{\"eventId\":\"not-a-uuid\",\"type\":\"ORDER_CREATED\",\"orderId\":\"x\",\"occurredAt\":\"2026-10-19T10:15:30Z\"}",
            "{\"eventId\":\"6b1f8a1e-1e5e-4a43-9a8f-0f6f1f9d8a11\",\"type\":\"NOT_A_TYPE\",\"orderId\":\"6b1f8a1e-1e5e-4a43-9a8f-0f6f1f9d8a12\",\"occurredAt\":\"2026-10-19T10:15:30Z\"}",
    })
    void malformedBodiesArePermanentFailures(String body) {
        assertThatThrownBy(() -> codec.decode(body)).isInstanceOf(MalformedEventException.class);
    }

    @Test
    void orderCreatedWithoutItemsIsMalformed() {
        String json = """
                {"eventId":"%s","type":"ORDER_CREATED","orderId":"%s","occurredAt":"2026-10-19T10:15:30Z","amount":1}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("items must not be empty");
    }

    @Test
    void lineItemWithNonPositiveQuantityIsMalformed() {
        String json = """
                {"eventId":"%s","type":"ORDER_CREATED","orderId":"%s","occurredAt":"2026-10-19T10:15:30Z","amount":1,
                 "items":[{"sku":"SKU-001","quantity":0,"unitPrice":1}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(MalformedEventException.class);
    }
}
