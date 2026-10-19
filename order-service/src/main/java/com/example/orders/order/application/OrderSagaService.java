package com.example.orders.order.application;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.messaging.PermanentMessageException;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderNotFoundException;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.domain.PaymentStatus;
import com.example.orders.order.metrics.OrderMetrics;
import com.example.orders.order.outbox.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies saga outcome events to orders. All decisions about what an event means live in
 * {@link Order}; this service loads the order, applies the event, persists the result and, for
 * a payment failure, schedules the compensating INVENTORY_RELEASE through the outbox in the same
 * transaction.
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);
    /** INVENTORY_RESERVED and PAYMENT_COMPLETED for one order often arrive together. */
    static final int MAX_OPTIMISTIC_LOCK_ATTEMPTS = 5;

    private final OrderRepository orders;
    private final Outbox outbox;
    private final TransactionTemplate transactionTemplate;
    private final OrderMetrics metrics;
    private final Clock clock;

    public OrderSagaService(OrderRepository orders, Outbox outbox, TransactionTemplate transactionTemplate,
                            OrderMetrics metrics, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @throws OrderNotFoundException (transient: retried with backoff, then dead-lettered)
     * @throws PermanentMessageException if the event type does not belong on this queue
     */
    public void handle(OrderEvent event) {
        for (int attempt = 1; ; attempt++) {
            try {
                Outcome outcome = transactionTemplate.execute(status -> apply(event));
                if (outcome != null && outcome.statusChanged()) {
                    metrics.transitioned(outcome.order(), outcome.at());
                }
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= MAX_OPTIMISTIC_LOCK_ATTEMPTS) {
                    throw e;
                }
                log.debug("Concurrent update of order {} while applying {}, retrying (attempt {})",
                        event.orderId(), event.type(), attempt);
                backOffBriefly(attempt);
            }
        }
    }

    private Outcome apply(OrderEvent event) {
        Order order = orders.findById(event.orderId()).orElseThrow(() -> new OrderNotFoundException(event.orderId()));
        Instant now = clock.instant();
        OrderStatus before = order.status();

        boolean changed = switch (event.type()) {
            case INVENTORY_RESERVED -> order.markInventoryReserved(now);
            case INVENTORY_FAILED -> order.markInventoryFailed(event.reason(), now);
            case PAYMENT_COMPLETED -> order.markPaymentCompleted(now);
            case PAYMENT_FAILED -> order.markPaymentFailed(event.reason(), now);
            case ORDER_CREATED, INVENTORY_RELEASE -> throw new PermanentMessageException(
                    event.type() + " does not belong on " + QueueNames.ORDER_EVENTS);
        };

        if (!changed) {
            if (event.type() == EventType.PAYMENT_COMPLETED
                    && order.status() == OrderStatus.FAILED && order.paymentStatus() == PaymentStatus.PENDING) {
                // Cannot happen in this saga (payment only runs after a reservation), but if it ever
                // does the customer was charged for a failed order and needs a refund.
                metrics.anomaly("payment_completed_for_failed_order");
                log.error("Order {} is FAILED but payment {} completed; manual refund required",
                        order.id(), event.transactionId());
            }
            log.info("Ignored {} for order {} (status {}): duplicate or stale", event.type(), order.id(), before);
            return null;
        }

        if (event.type() == EventType.PAYMENT_FAILED) {
            outbox.enqueue(QueueNames.INVENTORY, OrderEvent.inventoryRelease(order.id(), "payment failed", now));
        }
        orders.save(order);
        log.info("Order {} {} -> {} after {}", order.id(), before, order.status(), event.type());
        return new Outcome(order, before != order.status(), now);
    }

    private static void backOffBriefly(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Outcome(Order order, boolean statusChanged, Instant at) {
    }
}
