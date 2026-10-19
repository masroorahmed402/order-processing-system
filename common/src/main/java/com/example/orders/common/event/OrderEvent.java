package com.example.orders.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The single message envelope used on every queue. Fields that do not apply to a given
 * {@link EventType} are null and omitted from the JSON.
 *
 * <p>{@code eventId} is unique per publication; consumers deduplicate on {@code orderId} plus
 * their own state, because at-least-once delivery may redeliver the same event and a
 * republished outcome carries a new {@code eventId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderEvent(
        UUID eventId,
        EventType type,
        UUID orderId,
        Instant occurredAt,
        String customerId,
        List<LineItem> items,
        BigDecimal amount,
        String reason,
        String transactionId) {

    public OrderEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static OrderEvent orderCreated(UUID orderId, String customerId, List<LineItem> items,
                                          BigDecimal amount, Instant now) {
        return new OrderEvent(UUID.randomUUID(), EventType.ORDER_CREATED, orderId, now, customerId,
                items, amount, null, null).validated();
    }

    /** Built by inventory from the ORDER_CREATED it reserved, so payment knows what to charge. */
    public static OrderEvent inventoryReserved(UUID orderId, String customerId, List<LineItem> items,
                                               BigDecimal amount, Instant now) {
        return new OrderEvent(UUID.randomUUID(), EventType.INVENTORY_RESERVED, orderId, now, customerId,
                items, amount, null, null).validated();
    }

    public static OrderEvent inventoryFailed(UUID orderId, String reason, Instant now) {
        return new OrderEvent(UUID.randomUUID(), EventType.INVENTORY_FAILED, orderId, now, null,
                null, null, reason, null).validated();
    }

    public static OrderEvent paymentCompleted(UUID orderId, BigDecimal amount, String transactionId, Instant now) {
        return new OrderEvent(UUID.randomUUID(), EventType.PAYMENT_COMPLETED, orderId, now, null,
                null, amount, null, transactionId).validated();
    }

    public static OrderEvent paymentFailed(UUID orderId, BigDecimal amount, String reason, Instant now) {
        return new OrderEvent(UUID.randomUUID(), EventType.PAYMENT_FAILED, orderId, now, null,
                null, amount, reason, null).validated();
    }

    public static OrderEvent inventoryRelease(UUID orderId, String reason, Instant now) {
        return new OrderEvent(UUID.randomUUID(), EventType.INVENTORY_RELEASE, orderId, now, null,
                null, null, reason, null).validated();
    }

    /**
     * Checks the fields each event type needs. Throws {@link IllegalArgumentException} so
     * {@link OrderEventCodec} can turn it into a permanent (non-retryable) failure.
     */
    public OrderEvent validated() {
        switch (type) {
            case ORDER_CREATED, INVENTORY_RESERVED -> {
                require(!items.isEmpty(), "items must not be empty");
                require(amount != null && amount.signum() >= 0, "amount must be present and non-negative");
            }
            case INVENTORY_FAILED, PAYMENT_FAILED -> require(reason != null && !reason.isBlank(), "reason is required");
            case PAYMENT_COMPLETED -> require(transactionId != null && !transactionId.isBlank(), "transactionId is required");
            case INVENTORY_RELEASE -> {
                // orderId is enough: inventory looks up what it reserved for the order.
            }
        }
        return this;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(type + ": " + message);
        }
    }
}
