package com.example.orders.inventory.application;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.messaging.EventPublisher;
import com.example.orders.common.messaging.PermanentMessageException;
import com.example.orders.inventory.domain.InventoryRepository;
import com.example.orders.inventory.domain.ReleaseResult;
import com.example.orders.inventory.domain.Reservation;
import com.example.orders.inventory.domain.ReserveResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Reacts to ORDER_CREATED (reserve stock) and INVENTORY_RELEASE (compensation).
 *
 * <p>A duplicate ORDER_CREATED re-publishes the outcome stored the first time instead of
 * re-evaluating stock. That makes redelivery safe even if the process died after writing to
 * DynamoDB but before publishing: the redelivered message completes the publish.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventory;
    private final EventPublisher publisher;
    private final MeterRegistry metrics;
    private final Clock clock;

    public InventoryService(InventoryRepository inventory, EventPublisher publisher, MeterRegistry metrics, Clock clock) {
        this.inventory = inventory;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void handle(OrderEvent event) {
        switch (event.type()) {
            case ORDER_CREATED -> reserve(event);
            case INVENTORY_RELEASE -> release(event);
            default -> throw new PermanentMessageException(event.type() + " does not belong on " + QueueNames.INVENTORY);
        }
    }

    private void reserve(OrderEvent event) {
        ReserveResult result = inventory.reserve(event.orderId(), event.customerId(), event.amount(), event.items(),
                clock.instant());
        switch (result) {
            case ReserveResult.Reserved r -> {
                count("inventory.reservations", "reserved");
                log.info("Reserved stock for order {}", event.orderId());
                publishOutcome(r.reservation());
            }
            case ReserveResult.Rejected r -> {
                count("inventory.reservations", "rejected");
                log.info("Rejected order {}: {}", event.orderId(), r.reservation().reason());
                publishOutcome(r.reservation());
            }
            case ReserveResult.AlreadyProcessed r -> {
                count("inventory.reservations", "duplicate");
                log.info("Duplicate ORDER_CREATED for order {} (stored outcome {}), re-publishing outcome",
                        event.orderId(), r.reservation().status());
                publishOutcome(r.reservation());
            }
        }
    }

    private void publishOutcome(Reservation reservation) {
        switch (reservation.status()) {
            case RESERVED -> {
                OrderEvent reserved = OrderEvent.inventoryReserved(reservation.orderId(), reservation.customerId(),
                        reservation.items(), reservation.amount(), clock.instant());
                // Payment first: if we crash in between, the redelivery publishes both again.
                publisher.publish(QueueNames.PAYMENT, reserved);
                publisher.publish(QueueNames.ORDER_EVENTS, reserved);
            }
            case REJECTED -> publisher.publish(QueueNames.ORDER_EVENTS,
                    OrderEvent.inventoryFailed(reservation.orderId(), reservation.reason(), clock.instant()));
            case RELEASED -> {
                // Already compensated; the order is terminal. Nothing to tell anyone.
            }
        }
    }

    private void release(OrderEvent event) {
        ReleaseResult result = inventory.release(event.orderId(), clock.instant());
        count("inventory.releases", result.name().toLowerCase());
        log.info("Release for order {}: {}", event.orderId(), result);
    }

    private void count(String name, String outcome) {
        metrics.counter(name, "outcome", outcome).increment();
    }
}
