package com.example.orders.payment.application;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.LineItem;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.messaging.EventPublisher;
import com.example.orders.common.messaging.PermanentMessageException;
import com.example.orders.payment.domain.PaymentInProgressException;
import com.example.orders.payment.domain.PaymentRecord;
import com.example.orders.payment.domain.PaymentRepository;
import com.example.orders.payment.domain.PaymentStatus;
import com.example.orders.payment.gateway.ChargeResult;
import com.example.orders.payment.gateway.PaymentGatewayProperties;
import com.example.orders.payment.gateway.SimulatedPaymentGateway;
import com.example.orders.payment.gateway.TransientPaymentException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private final InMemoryPayments payments = new InMemoryPayments();
    private final PaymentGatewayProperties gatewayProperties = new PaymentGatewayProperties();
    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(gatewayProperties, () -> 0.5);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PaymentService service = new PaymentService(payments, gateway, publisher, registry,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(20));

    private static OrderEvent reserved(UUID orderId, String amount) {
        return OrderEvent.inventoryReserved(orderId, "c", List.of(new LineItem("SKU-001", 1, new BigDecimal(amount))),
                new BigDecimal(amount), NOW);
    }

    @Test
    void approvedChargeIsStoredAndPublishedAsCompleted() {
        UUID orderId = UUID.randomUUID();

        service.handle(reserved(orderId, "10.00"));

        assertThat(payments.find(orderId)).get().extracting(PaymentRecord::status).isEqualTo(PaymentStatus.COMPLETED);
        OrderEvent published = published();
        assertThat(published.type()).isEqualTo(EventType.PAYMENT_COMPLETED);
        assertThat(published.transactionId()).startsWith("sim_");
        assertThat(gateway.chargeCount(orderId)).isEqualTo(1);
    }

    @Test
    void declineIsStoredAndPublishedAsFailed() {
        UUID orderId = UUID.randomUUID();

        service.handle(reserved(orderId, "9999.00"));

        assertThat(payments.find(orderId)).get().extracting(PaymentRecord::status).isEqualTo(PaymentStatus.DECLINED);
        OrderEvent published = published();
        assertThat(published.type()).isEqualTo(EventType.PAYMENT_FAILED);
        assertThat(published.reason()).contains("exceeds limit");
    }

    @Test
    void duplicateDeliveryRepublishesTheStoredResultWithoutCharging() {
        UUID orderId = UUID.randomUUID();
        service.handle(reserved(orderId, "10.00"));

        service.handle(reserved(orderId, "10.00"));
        service.handle(reserved(orderId, "10.00"));

        assertThat(gateway.chargeCount(orderId)).isEqualTo(1);
        ArgumentCaptor<OrderEvent> events = ArgumentCaptor.forClass(OrderEvent.class);
        verify(publisher, times(3)).publish(eq(QueueNames.ORDER_EVENTS), events.capture());
        assertThat(events.getAllValues()).extracting(OrderEvent::transactionId).containsOnly(events.getValue().transactionId());
        assertThat(registry.get("payments.processed").tag("outcome", "duplicate").counter().count()).isEqualTo(2.0);
    }

    @Test
    void transientGatewayFailureReleasesTheClaimAndRethrows() {
        gatewayProperties.setTransientFailureRate(1.0);
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> service.handle(reserved(orderId, "10.00"))).isInstanceOf(TransientPaymentException.class);

        assertThat(payments.find(orderId)).isEmpty();
        verifyNoInteractions(publisher);

        gatewayProperties.setTransientFailureRate(0.0);
        service.handle(reserved(orderId, "10.00"));
        assertThat(payments.find(orderId)).get().extracting(PaymentRecord::status).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void freshClaimByAnotherConsumerIsATransientConflict() {
        UUID orderId = UUID.randomUUID();
        payments.claim(orderId, BigDecimal.TEN, "other-consumer", NOW.minusSeconds(5));

        assertThatThrownBy(() -> service.handle(reserved(orderId, "10.00"))).isInstanceOf(PaymentInProgressException.class);
        assertThat(gateway.chargeCount(orderId)).isZero();
    }

    @Test
    void staleClaimIsTakenOverAndCompleted() {
        UUID orderId = UUID.randomUUID();
        payments.claim(orderId, BigDecimal.TEN, "crashed-consumer", NOW.minusSeconds(60));

        service.handle(reserved(orderId, "10.00"));

        assertThat(payments.find(orderId)).get().extracting(PaymentRecord::status).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(gateway.chargeCount(orderId)).isEqualTo(1);
    }

    @Test
    void wrongEventTypeIsPermanent() {
        assertThatThrownBy(() -> service.handle(OrderEvent.inventoryRelease(UUID.randomUUID(), "x", NOW)))
                .isInstanceOf(PermanentMessageException.class);
    }

    private OrderEvent published() {
        ArgumentCaptor<OrderEvent> event = ArgumentCaptor.forClass(OrderEvent.class);
        verify(publisher).publish(eq(QueueNames.ORDER_EVENTS), event.capture());
        return event.getValue();
    }

    /** Same conditional semantics as the DynamoDB implementation. */
    static class InMemoryPayments implements PaymentRepository {
        private final Map<UUID, PaymentRecord> rows = new ConcurrentHashMap<>();

        @Override
        public Optional<PaymentRecord> find(UUID orderId) {
            return Optional.ofNullable(rows.get(orderId));
        }

        @Override
        public boolean claim(UUID orderId, BigDecimal amount, String token, Instant now) {
            return rows.putIfAbsent(orderId, new PaymentRecord(orderId, PaymentStatus.CHARGING, amount, null, null, token, now, now)) == null;
        }

        @Override
        public synchronized boolean takeOver(UUID orderId, String staleToken, String newToken, Instant now) {
            PaymentRecord r = rows.get(orderId);
            if (r == null || r.status() != PaymentStatus.CHARGING || !r.claimToken().equals(staleToken)) {
                return false;
            }
            rows.put(orderId, new PaymentRecord(orderId, PaymentStatus.CHARGING, r.amount(), null, null, newToken, now, now));
            return true;
        }

        @Override
        public synchronized Optional<PaymentRecord> complete(UUID orderId, String token, ChargeResult result, Instant now) {
            PaymentRecord r = rows.get(orderId);
            if (r == null || r.status() != PaymentStatus.CHARGING || !r.claimToken().equals(token)) {
                return Optional.empty();
            }
            PaymentRecord done = switch (result) {
                case ChargeResult.Approved a -> new PaymentRecord(orderId, PaymentStatus.COMPLETED, r.amount(), a.transactionId(), null, token, r.claimedAt(), now);
                case ChargeResult.Declined d -> new PaymentRecord(orderId, PaymentStatus.DECLINED, r.amount(), null, d.reason(), token, r.claimedAt(), now);
            };
            rows.put(orderId, done);
            return Optional.of(done);
        }

        @Override
        public synchronized void releaseClaim(UUID orderId, String token) {
            PaymentRecord r = rows.get(orderId);
            if (r != null && r.status() == PaymentStatus.CHARGING && r.claimToken().equals(token)) {
                rows.remove(orderId);
            }
        }
    }
}
