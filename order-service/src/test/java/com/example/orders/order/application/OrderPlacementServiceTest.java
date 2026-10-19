package com.example.orders.order.application;

import com.example.orders.common.event.LineItem;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderItem;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.idempotency.ClaimResult;
import com.example.orders.order.idempotency.IdempotencyKeyReuseException;
import com.example.orders.order.idempotency.IdempotencyProperties;
import com.example.orders.order.idempotency.IdempotencyRecord;
import com.example.orders.order.idempotency.IdempotencyStore;
import com.example.orders.order.idempotency.RequestFingerprint;
import com.example.orders.order.idempotency.RequestInProgressException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPlacementServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final PlaceOrderCommand COMMAND = new PlaceOrderCommand("customer-1",
            List.of(new LineItem("SKU-001", 1, new BigDecimal("10.00"))));

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final OrderCreator creator = mock(OrderCreator.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OrderPlacementService service = new OrderPlacementService(store, creator, orders,
            new IdempotencyProperties("t", Duration.ofHours(1), Duration.ZERO, Duration.ofMillis(1), Duration.ofSeconds(30)),
            clock);

    @Test
    void firstRequestClaimsTheKeyCreatesTheOrderAndCompletesTheKey() {
        when(creator.createIfAbsent(any(), eq("key-1"), eq(COMMAND)))
                .thenAnswer(inv -> new PlacementResult(order(inv.getArgument(0)), true));

        PlacementResult result = service.place("key-1", COMMAND);

        assertThat(result.created()).isTrue();
        IdempotencyRecord record = store.records.get("key-1");
        assertThat(record.status()).isEqualTo(IdempotencyRecord.Status.COMPLETED);
        assertThat(record.orderId()).isEqualTo(result.order().id());
    }

    @Test
    void replayOfACompletedKeyReturnsTheOriginalOrderWithoutCreating() {
        Order original = order(UUID.randomUUID());
        store.records.put("key-1", new IdempotencyRecord("key-1", RequestFingerprint.of(COMMAND), original.id(),
                IdempotencyRecord.Status.COMPLETED, NOW));
        when(orders.findById(original.id())).thenReturn(Optional.of(original));

        PlacementResult result = service.place("key-1", COMMAND);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(original);
        verify(creator, never()).createIfAbsent(any(), any(), any());
    }

    @Test
    void reusingAKeyWithADifferentBodyIsRejected() {
        store.records.put("key-1", new IdempotencyRecord("key-1", "some-other-fingerprint", UUID.randomUUID(),
                IdempotencyRecord.Status.COMPLETED, NOW));

        assertThatThrownBy(() -> service.place("key-1", COMMAND)).isInstanceOf(IdempotencyKeyReuseException.class);
        verify(creator, never()).createIfAbsent(any(), any(), any());
    }

    @Test
    void duplicateWhileOriginalIsInFlightReturnsTheOrderOnceItExistsAndCompletesTheKey() {
        Order original = order(UUID.randomUUID());
        store.records.put("key-1", new IdempotencyRecord("key-1", RequestFingerprint.of(COMMAND), original.id(),
                IdempotencyRecord.Status.IN_PROGRESS, NOW));
        when(orders.findById(original.id())).thenReturn(Optional.of(original));

        PlacementResult result = service.place("key-1", COMMAND);

        assertThat(result.created()).isFalse();
        assertThat(result.order().id()).isEqualTo(original.id());
        assertThat(store.records.get("key-1").isCompleted()).isTrue();
    }

    @Test
    void duplicateWhileOriginalIsStillInFlightGets409() {
        UUID orderId = UUID.randomUUID();
        store.records.put("key-1", new IdempotencyRecord("key-1", RequestFingerprint.of(COMMAND), orderId,
                IdempotencyRecord.Status.IN_PROGRESS, NOW.minusSeconds(5)));
        when(orders.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place("key-1", COMMAND)).isInstanceOf(RequestInProgressException.class);
        verify(creator, never()).createIfAbsent(any(), any(), any());
    }

    @Test
    void staleClaimIsTakenOverUsingTheOriginallyReservedOrderId() {
        UUID orderId = UUID.randomUUID();
        store.records.put("key-1", new IdempotencyRecord("key-1", RequestFingerprint.of(COMMAND), orderId,
                IdempotencyRecord.Status.IN_PROGRESS, NOW.minusSeconds(60)));
        when(orders.findById(orderId)).thenReturn(Optional.empty());
        when(creator.createIfAbsent(orderId, "key-1", COMMAND)).thenReturn(new PlacementResult(order(orderId), true));

        PlacementResult result = service.place("key-1", COMMAND);

        assertThat(result.order().id()).isEqualTo(orderId);
        assertThat(store.records.get("key-1").isCompleted()).isTrue();
    }

    private static Order order(UUID id) {
        return Order.place(id, "key-1", "customer-1", List.of(new OrderItem("SKU-001", 1, new BigDecimal("10.00"))), NOW);
    }

    /** Behaves like the DynamoDB conditional put: first writer wins. */
    static class InMemoryIdempotencyStore implements IdempotencyStore {
        final Map<String, IdempotencyRecord> records = new HashMap<>();

        @Override
        public synchronized ClaimResult claim(String key, String fingerprint, UUID orderId, Instant now) {
            IdempotencyRecord existing = records.get(key);
            if (existing != null) {
                return new ClaimResult.AlreadyClaimed(existing);
            }
            records.put(key, new IdempotencyRecord(key, fingerprint, orderId, IdempotencyRecord.Status.IN_PROGRESS, now));
            return new ClaimResult.Claimed();
        }

        @Override
        public synchronized void markCompleted(String key, UUID orderId, Instant now) {
            IdempotencyRecord r = records.get(key);
            records.put(key, new IdempotencyRecord(key, r.fingerprint(), orderId, IdempotencyRecord.Status.COMPLETED, r.createdAt()));
        }
    }
}
