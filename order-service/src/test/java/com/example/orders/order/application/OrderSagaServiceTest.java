package com.example.orders.order.application;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.LineItem;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.messaging.PermanentMessageException;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderItem;
import com.example.orders.order.domain.OrderNotFoundException;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.metrics.OrderMetrics;
import com.example.orders.order.outbox.Outbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderSagaServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final Outbox outbox = mock(Outbox.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrderSagaService saga = new OrderSagaService(orders, outbox, tx, new OrderMetrics(registry),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private Order order;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        order = Order.place(UUID.randomUUID(), "k", "c", List.of(new OrderItem("SKU-001", 1, BigDecimal.TEN)), NOW.minusSeconds(3));
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    }

    @Test
    void happyPathConfirmsAndRecordsMetrics() {
        saga.handle(OrderEvent.inventoryReserved(order.id(), "c", order.lineItems(), BigDecimal.TEN, NOW));
        saga.handle(OrderEvent.paymentCompleted(order.id(), BigDecimal.TEN, "tx-1", NOW));

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orders, times(2)).save(order);
        assertThat(registry.get(OrderMetrics.TRANSITIONS).tag("status", "CONFIRMED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(OrderMetrics.SAGA_DURATION).tag("status", "CONFIRMED").timer().count()).isEqualTo(1);
        verify(outbox, never()).enqueue(any(), any());
    }

    @Test
    void paymentFailureSchedulesExactlyOneInventoryRelease() {
        saga.handle(OrderEvent.inventoryReserved(order.id(), "c", order.lineItems(), BigDecimal.TEN, NOW));
        OrderEvent failed = OrderEvent.paymentFailed(order.id(), BigDecimal.TEN, "declined", NOW);

        saga.handle(failed);
        saga.handle(failed); // duplicate delivery

        assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
        verify(outbox, times(1)).enqueue(eq(QueueNames.INVENTORY),
                argThat(e -> e.type() == EventType.INVENTORY_RELEASE && e.orderId().equals(order.id())));
    }

    @Test
    void earlyPaymentIsRecordedWithoutAStatusChangeMetric() {
        saga.handle(OrderEvent.paymentCompleted(order.id(), BigDecimal.TEN, "tx-1", NOW));

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        verify(orders).save(order);
        assertThat(registry.find(OrderMetrics.TRANSITIONS).counters()).isEmpty();
    }

    @Test
    void duplicateEventsAreIgnoredWithoutSaving() {
        OrderEvent reserved = OrderEvent.inventoryReserved(order.id(), "c", order.lineItems(), BigDecimal.TEN, NOW);
        saga.handle(reserved);
        saga.handle(reserved);

        verify(orders, times(1)).save(order);
    }

    @Test
    void inventoryFailureFailsTheOrder() {
        saga.handle(OrderEvent.inventoryFailed(order.id(), "insufficient stock for SKU-001", NOW));

        assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.failureReason()).contains("insufficient stock");
    }

    @Test
    void paymentCompletedForAFailedOrderIsFlaggedAsAnAnomaly() {
        saga.handle(OrderEvent.inventoryFailed(order.id(), "out of stock", NOW));
        saga.handle(OrderEvent.paymentCompleted(order.id(), BigDecimal.TEN, "tx-1", NOW));

        assertThat(registry.get(OrderMetrics.ANOMALIES).counter().count()).isEqualTo(1.0);
    }

    @Test
    void unknownOrderIsATransientFailure() {
        UUID unknown = UUID.randomUUID();
        when(orders.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> saga.handle(OrderEvent.paymentCompleted(unknown, BigDecimal.ONE, "tx", NOW)))
                .isInstanceOf(OrderNotFoundException.class)
                .isNotInstanceOf(PermanentMessageException.class);
    }

    @Test
    void eventsThatDoNotBelongOnTheQueueArePermanentFailures() {
        OrderEvent created = OrderEvent.orderCreated(order.id(), "c",
                List.of(new LineItem("SKU-001", 1, BigDecimal.TEN)), BigDecimal.TEN, NOW);

        assertThatThrownBy(() -> saga.handle(created)).isInstanceOf(PermanentMessageException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void optimisticLockConflictsAreRetriedInProcess() {
        doThrow(new ObjectOptimisticLockingFailureException(Order.class, order.id()))
                .doAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null))
                .when(tx).execute(any());

        saga.handle(OrderEvent.inventoryReserved(order.id(), "c", order.lineItems(), BigDecimal.TEN, NOW));

        assertThat(order.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        verify(tx, times(2)).execute(any());
    }

    @Test
    void persistentOptimisticLockConflictsEventuallyPropagate() {
        doThrow(new ObjectOptimisticLockingFailureException(Order.class, order.id())).when(tx).execute(any());

        assertThatThrownBy(() -> saga.handle(OrderEvent.paymentCompleted(order.id(), BigDecimal.TEN, "tx", NOW)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(tx, times(OrderSagaService.MAX_OPTIMISTIC_LOCK_ATTEMPTS)).execute(any());
    }
}
