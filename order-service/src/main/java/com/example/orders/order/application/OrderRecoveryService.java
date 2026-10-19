package com.example.orders.order.application;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderNotFoundException;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.outbox.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Operator tool for an order stuck in a non-terminal state (see RUNBOOK in README).
 *
 * <p>Re-emitting ORDER_CREATED is always safe because every hop is idempotent: inventory
 * re-publishes its stored reservation outcome, payment re-publishes its stored charge result,
 * and the order ignores events it has already applied. So the saga resumes from wherever it
 * stopped without double-reserving or double-charging.
 */
@Service
public class OrderRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryService.class);

    private final OrderRepository orders;
    private final Outbox outbox;
    private final Clock clock;

    public OrderRecoveryService(OrderRepository orders, Outbox outbox, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** @return false if the order is already terminal and nothing was re-emitted. */
    @Transactional
    public boolean resume(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.isTerminal()) {
            return false;
        }
        Instant now = clock.instant();
        outbox.enqueue(QueueNames.INVENTORY, OrderEvent.orderCreated(order.id(), order.customerId(),
                order.lineItems(), order.totalAmount(), now));
        log.warn("Re-emitted ORDER_CREATED for stuck order {} (status {})", orderId, order.status());
        return true;
    }
}
