package com.example.orders.order.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant T0 = Instant.parse("2026-10-19T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);
    private static final Instant T2 = T0.plusSeconds(2);

    private static Order newOrder() {
        return Order.place(UUID.randomUUID(), "key-1", "customer-1",
                List.of(new OrderItem("SKU-001", 2, new BigDecimal("19.99")), new OrderItem("SKU-002", 1, new BigDecimal("5"))),
                T0);
    }

    @Test
    void newOrderIsPendingWithComputedTotal() {
        Order order = newOrder();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.inventoryStatus()).isEqualTo(InventoryStatus.PENDING);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.totalAmount()).isEqualByComparingTo("44.98");
        assertThat(order.totalAmount().scale()).isEqualTo(2);
        assertThat(order.lineItems()).hasSize(2);
        assertThat(order.isTerminal()).isFalse();
        assertThat(order.createdAt()).isEqualTo(T0);
    }

    @Test
    void placingRequiresItemsKeyAndCustomer() {
        assertThatThrownBy(() -> Order.place(UUID.randomUUID(), "k", "c", List.of(), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.place(UUID.randomUUID(), " ", "c",
                List.of(new OrderItem("SKU", 1, BigDecimal.ONE)), T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.place(UUID.randomUUID(), "k", null,
                List.of(new OrderItem("SKU", 1, BigDecimal.ONE)), T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderItem("SKU", 0, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    class HappyPath {

        @Test
        void reservedThenPaidIsConfirmed() {
            Order order = newOrder();

            assertThat(order.markInventoryReserved(T1)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);

            assertThat(order.markPaymentCompleted(T2)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.isTerminal()).isTrue();
            assertThat(order.updatedAt()).isEqualTo(T2);
            assertThat(order.failureReason()).isNull();
        }
    }

    @Nested
    class OutOfOrderDelivery {

        @Test
        void paymentCompletedBeforeInventoryReservedStillEndsConfirmed() {
            Order order = newOrder();

            assertThat(order.markPaymentCompleted(T1)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.COMPLETED);

            assertThat(order.markInventoryReserved(T2)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        void paymentFailedBeforeInventoryReservedFailsAndIgnoresTheLateReservation() {
            Order order = newOrder();

            assertThat(order.markPaymentFailed("card declined", T1)).isTrue();
            assertThat(order.status()).isEqualTo(OrderStatus.FAILED);

            assertThat(order.markInventoryReserved(T2)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
            assertThat(order.inventoryStatus()).isEqualTo(InventoryStatus.PENDING);
        }

        @Test
        void eventsAfterInventoryFailureAreStale() {
            Order order = newOrder();
            order.markInventoryFailed("out of stock", T1);

            assertThat(order.markInventoryReserved(T2)).isFalse();
            assertThat(order.markPaymentCompleted(T2)).isFalse();
            assertThat(order.markPaymentFailed("x", T2)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
            assertThat(order.failureReason()).isEqualTo("Inventory: out of stock");
            assertThat(order.updatedAt()).isEqualTo(T1);
        }
    }

    @Nested
    class DuplicateDelivery {

        @Test
        void duplicateReservationIsANoOp() {
            Order order = newOrder();
            assertThat(order.markInventoryReserved(T1)).isTrue();

            assertThat(order.markInventoryReserved(T2)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
            assertThat(order.updatedAt()).isEqualTo(T1);
        }

        @Test
        void duplicatePaymentCompletedIsANoOp() {
            Order order = newOrder();
            order.markPaymentCompleted(T1);

            assertThat(order.markPaymentCompleted(T2)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        }

        @Test
        void duplicatePaymentFailedReturnsTrueOnlyOnceSoCompensationRunsOnce() {
            Order order = newOrder();
            order.markInventoryReserved(T1);

            assertThat(order.markPaymentFailed("declined", T2)).isTrue();
            assertThat(order.markPaymentFailed("declined", T2)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
            assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(order.failureReason()).isEqualTo("Payment: declined");
        }

        @Test
        void duplicateInventoryFailedIsANoOp() {
            Order order = newOrder();
            assertThat(order.markInventoryFailed("out of stock", T1)).isTrue();
            assertThat(order.markInventoryFailed("out of stock", T2)).isFalse();
        }
    }

    @Nested
    class TerminalStates {

        @Test
        void confirmedOrderIgnoresEverything() {
            Order order = newOrder();
            order.markInventoryReserved(T1);
            order.markPaymentCompleted(T1);

            assertThat(order.markPaymentFailed("late", T2)).isFalse();
            assertThat(order.markInventoryFailed("late", T2)).isFalse();
            assertThat(order.markInventoryReserved(T2)).isFalse();
            assertThat(order.markPaymentCompleted(T2)).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @Test
        void longFailureReasonsAreTruncatedToTheColumnSize() {
            Order order = newOrder();
            order.markInventoryFailed("x".repeat(800), T1);

            assertThat(order.failureReason()).hasSize(500);
        }
    }
}
