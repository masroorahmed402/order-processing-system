package com.example.orders.order.application;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.idempotency.ClaimResult;
import com.example.orders.order.idempotency.IdempotencyKeyReuseException;
import com.example.orders.order.idempotency.IdempotencyProperties;
import com.example.orders.order.idempotency.IdempotencyRecord;
import com.example.orders.order.idempotency.IdempotencyStore;
import com.example.orders.order.idempotency.RequestFingerprint;
import com.example.orders.order.idempotency.RequestInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent order placement.
 *
 * <ol>
 *   <li>Claim the Idempotency-Key in DynamoDB together with a freshly generated order id
 *   (conditional put, so exactly one request wins).</li>
 *   <li>The winner writes the order with that id. Every other request with the key is mapped
 *   to the same id, and the order id is the Postgres primary key, so a second order for one
 *   key cannot exist even if requests race.</li>
 *   <li>Replays with the same body get the original order (HTTP 200); a different body is
 *   rejected (422).</li>
 * </ol>
 */
@Service
public class OrderPlacementService {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

    private final IdempotencyStore idempotencyStore;
    private final OrderCreator orderCreator;
    private final OrderRepository orders;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public OrderPlacementService(IdempotencyStore idempotencyStore, OrderCreator orderCreator, OrderRepository orders,
                                 IdempotencyProperties properties, Clock clock) {
        this.idempotencyStore = idempotencyStore;
        this.orderCreator = orderCreator;
        this.orders = orders;
        this.properties = properties;
        this.clock = clock;
    }

    public PlacementResult place(String idempotencyKey, PlaceOrderCommand command) {
        String fingerprint = RequestFingerprint.of(command);
        UUID candidateOrderId = UUID.randomUUID();

        ClaimResult claim = idempotencyStore.claim(idempotencyKey, fingerprint, candidateOrderId, clock.instant());
        if (claim instanceof ClaimResult.Claimed) {
            return createAndComplete(idempotencyKey, candidateOrderId, command);
        }

        IdempotencyRecord existing = ((ClaimResult.AlreadyClaimed) claim).existing();
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReuseException(idempotencyKey);
        }
        Optional<Order> order = awaitOrder(existing);
        if (order.isPresent()) {
            if (!existing.isCompleted()) {
                idempotencyStore.markCompleted(idempotencyKey, existing.orderId(), clock.instant());
            }
            return new PlacementResult(order.get(), false);
        }
        if (existing.isStale(clock.instant(), properties.staleAfter())) {
            // The original request claimed the key and then died before writing the order.
            log.warn("Taking over stale idempotency claim {} for order {}", idempotencyKey, existing.orderId());
            return createAndComplete(idempotencyKey, existing.orderId(), command);
        }
        throw new RequestInProgressException(idempotencyKey);
    }

    private PlacementResult createAndComplete(String idempotencyKey, UUID orderId, PlaceOrderCommand command) {
        PlacementResult result = orderCreator.createIfAbsent(orderId, idempotencyKey, command);
        idempotencyStore.markCompleted(idempotencyKey, orderId, clock.instant());
        return result;
    }

    /** A concurrent duplicate waits briefly for the original request to commit the order. */
    private Optional<Order> awaitOrder(IdempotencyRecord existing) {
        Instant deadline = clock.instant().plus(properties.inProgressWait());
        while (true) {
            Optional<Order> order = orders.findById(existing.orderId());
            if (order.isPresent() || existing.isCompleted() || !clock.instant().isBefore(deadline)) {
                return order;
            }
            try {
                Thread.sleep(properties.pollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }
}
