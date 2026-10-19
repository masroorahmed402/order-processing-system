package com.example.orders.inventory.application;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.LineItem;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.messaging.EventPublisher;
import com.example.orders.common.messaging.PermanentMessageException;
import com.example.orders.inventory.domain.InventoryRepository;
import com.example.orders.inventory.domain.ReleaseResult;
import com.example.orders.inventory.domain.Reservation;
import com.example.orders.inventory.domain.ReserveResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final List<LineItem> ITEMS = List.of(new LineItem("SKU-001", 2, new BigDecimal("5.00")));

    private final InventoryRepository repository = mock(InventoryRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InventoryService service = new InventoryService(repository, publisher, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID orderId = UUID.randomUUID();
    private final OrderEvent created = OrderEvent.orderCreated(orderId, "c-1", ITEMS, new BigDecimal("10.00"), NOW);

    @Test
    void successfulReservationNotifiesPaymentAndOrderService() {
        Reservation reservation = Reservation.reserved(orderId, "c-1", new BigDecimal("10.00"), ITEMS, NOW);
        when(repository.reserve(eq(orderId), eq("c-1"), any(), eq(ITEMS), eq(NOW))).thenReturn(new ReserveResult.Reserved(reservation));

        service.handle(created);

        ArgumentCaptor<OrderEvent> toPayment = ArgumentCaptor.forClass(OrderEvent.class);
        ArgumentCaptor<OrderEvent> toOrders = ArgumentCaptor.forClass(OrderEvent.class);
        verify(publisher).publish(eq(QueueNames.PAYMENT), toPayment.capture());
        verify(publisher).publish(eq(QueueNames.ORDER_EVENTS), toOrders.capture());
        assertThat(toPayment.getValue().type()).isEqualTo(EventType.INVENTORY_RESERVED);
        assertThat(toPayment.getValue().amount()).isEqualByComparingTo("10.00");
        assertThat(toOrders.getValue()).isEqualTo(toPayment.getValue());
        assertThat(registry.get("inventory.reservations").tag("outcome", "reserved").counter().count()).isEqualTo(1.0);
    }

    @Test
    void rejectionOnlyNotifiesOrderService() {
        Reservation rejected = Reservation.reserved(orderId, "c-1", BigDecimal.TEN, ITEMS, NOW).rejected("insufficient stock for SKU-001");
        when(repository.reserve(any(), any(), any(), any(), any())).thenReturn(new ReserveResult.Rejected(rejected));

        service.handle(created);

        ArgumentCaptor<OrderEvent> event = ArgumentCaptor.forClass(OrderEvent.class);
        verify(publisher).publish(eq(QueueNames.ORDER_EVENTS), event.capture());
        assertThat(event.getValue().type()).isEqualTo(EventType.INVENTORY_FAILED);
        assertThat(event.getValue().reason()).isEqualTo("insufficient stock for SKU-001");
        verify(publisher, org.mockito.Mockito.never()).publish(eq(QueueNames.PAYMENT), any());
    }

    @Test
    void duplicateDeliveryRepublishesTheStoredOutcome() {
        Reservation stored = Reservation.reserved(orderId, "c-1", new BigDecimal("10.00"), ITEMS, NOW);
        when(repository.reserve(any(), any(), any(), any(), any())).thenReturn(new ReserveResult.AlreadyProcessed(stored));

        service.handle(created);

        verify(publisher).publish(eq(QueueNames.PAYMENT), any());
        verify(publisher).publish(eq(QueueNames.ORDER_EVENTS), any());
        assertThat(registry.get("inventory.reservations").tag("outcome", "duplicate").counter().count()).isEqualTo(1.0);
    }

    @Test
    void duplicateOfAnAlreadyReleasedOrderPublishesNothing() {
        when(repository.reserve(any(), any(), any(), any(), any()))
                .thenReturn(new ReserveResult.AlreadyProcessed(Reservation.releaseTombstone(orderId, NOW)));

        service.handle(created);

        verifyNoInteractions(publisher);
    }

    @Test
    void releaseDelegatesToTheRepository() {
        when(repository.release(orderId, NOW)).thenReturn(ReleaseResult.RELEASED);

        service.handle(OrderEvent.inventoryRelease(orderId, "payment failed", NOW));

        verify(repository).release(orderId, NOW);
        assertThat(registry.get("inventory.releases").tag("outcome", "released").counter().count()).isEqualTo(1.0);
        verifyNoInteractions(publisher);
    }

    @Test
    void eventsThatDoNotBelongHereArePermanentFailures() {
        assertThatThrownBy(() -> service.handle(OrderEvent.paymentCompleted(orderId, BigDecimal.ONE, "tx", NOW)))
                .isInstanceOf(PermanentMessageException.class);
    }

    @Test
    void quantitiesAreAggregatedPerSku() {
        Reservation r = Reservation.reserved(orderId, "c", BigDecimal.ONE, List.of(
                new LineItem("B", 1, BigDecimal.ONE), new LineItem("A", 2, BigDecimal.ONE), new LineItem("B", 3, BigDecimal.ONE)), NOW);

        assertThat(r.quantitiesBySku()).containsExactly(java.util.Map.entry("A", 2), java.util.Map.entry("B", 4));
    }
}
