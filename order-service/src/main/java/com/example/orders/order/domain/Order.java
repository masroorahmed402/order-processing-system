package com.example.orders.order.domain;

import com.example.orders.common.event.LineItem;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Order aggregate and its saga state machine.
 *
 * <p>The order tracks inventory and payment outcomes independently, because SQS does not
 * guarantee ordering: PAYMENT_COMPLETED can arrive before INVENTORY_RESERVED. The overall
 * {@link OrderStatus} is derived from the two:
 *
 * <pre>
 *   PENDING ──inventory reserved──▶ INVENTORY_RESERVED ──payment completed──▶ CONFIRMED
 *      │  └─payment completed (early)─▶ PENDING ──inventory reserved──▶ CONFIRMED
 *      └──inventory failed / payment failed──▶ FAILED
 * </pre>
 *
 * Every {@code mark*} method returns {@code false} when the event changes nothing (duplicate
 * delivery or a stale event for an order that already reached a terminal state), so callers can
 * skip side effects. Concurrent updates are detected by the {@link Version} column.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "total_amount", nullable = false, updatable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_status", nullable = false)
    private InventoryStatus inventoryStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Wrapper type so Spring Data treats a fresh order (null version) as new and persists it. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Order() {
        // for JPA
    }

    private Order(UUID id, String idempotencyKey, String customerId, List<OrderItem> items, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.customerId = requireText(customerId, "customerId");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one item");
        }
        this.items = new ArrayList<>(items);
        this.totalAmount = items.stream().map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        this.status = OrderStatus.PENDING;
        this.inventoryStatus = InventoryStatus.PENDING;
        this.paymentStatus = PaymentStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Order place(UUID id, String idempotencyKey, String customerId, List<OrderItem> items, Instant now) {
        return new Order(id, idempotencyKey, customerId, items, now);
    }

    /** Stock is held. Confirms the order if payment already completed (out-of-order delivery). */
    public boolean markInventoryReserved(Instant now) {
        if (status.isTerminal() || inventoryStatus != InventoryStatus.PENDING) {
            return false;
        }
        inventoryStatus = InventoryStatus.RESERVED;
        status = paymentStatus == PaymentStatus.COMPLETED ? OrderStatus.CONFIRMED : OrderStatus.INVENTORY_RESERVED;
        updatedAt = now;
        return true;
    }

    public boolean markInventoryFailed(String reason, Instant now) {
        if (status.isTerminal() || inventoryStatus != InventoryStatus.PENDING) {
            return false;
        }
        inventoryStatus = InventoryStatus.FAILED;
        fail("Inventory: " + reason, now);
        return true;
    }

    /**
     * Payment went through. If the reservation event has not arrived yet the order stays PENDING
     * with the payment recorded, and {@link #markInventoryReserved} will confirm it.
     */
    public boolean markPaymentCompleted(Instant now) {
        if (status.isTerminal() || paymentStatus != PaymentStatus.PENDING) {
            return false;
        }
        paymentStatus = PaymentStatus.COMPLETED;
        if (inventoryStatus == InventoryStatus.RESERVED) {
            status = OrderStatus.CONFIRMED;
        }
        updatedAt = now;
        return true;
    }

    /**
     * Payment declined. Returns true exactly once, and the caller must then release the
     * reserved stock (compensating transaction).
     */
    public boolean markPaymentFailed(String reason, Instant now) {
        if (status.isTerminal() || paymentStatus != PaymentStatus.PENDING) {
            return false;
        }
        paymentStatus = PaymentStatus.FAILED;
        fail("Payment: " + reason, now);
        return true;
    }

    private void fail(String reason, Instant now) {
        status = OrderStatus.FAILED;
        failureReason = reason.length() > 500 ? reason.substring(0, 500) : reason;
        updatedAt = now;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public List<LineItem> lineItems() {
        return items.stream().map(OrderItem::toLineItem).toList();
    }

    public UUID id() {
        return id;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String customerId() {
        return customerId;
    }

    public List<OrderItem> items() {
        return List.copyOf(items);
    }

    public BigDecimal totalAmount() {
        return totalAmount;
    }

    public OrderStatus status() {
        return status;
    }

    public InventoryStatus inventoryStatus() {
        return inventoryStatus;
    }

    public PaymentStatus paymentStatus() {
        return paymentStatus;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
