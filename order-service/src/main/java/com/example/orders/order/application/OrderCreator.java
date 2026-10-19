package com.example.orders.order.application;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderItem;
import com.example.orders.order.domain.OrderRepository;
import com.example.orders.order.metrics.OrderMetrics;
import com.example.orders.order.outbox.Outbox;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes a new order and its ORDER_CREATED outbox row in one Postgres transaction.
 * Creating the same order id twice is harmless: the second attempt returns the existing order.
 */
@Component
public class OrderCreator {

    private final OrderRepository orders;
    private final Outbox outbox;
    private final TransactionTemplate transactionTemplate;
    private final OrderMetrics metrics;
    private final Clock clock;

    public OrderCreator(OrderRepository orders, Outbox outbox, TransactionTemplate transactionTemplate,
                        OrderMetrics metrics, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
        this.clock = clock;
    }

    public PlacementResult createIfAbsent(UUID orderId, String idempotencyKey, PlaceOrderCommand command) {
        PlacementResult result;
        try {
            result = transactionTemplate.execute(status -> {
                var existing = orders.findById(orderId);
                if (existing.isPresent()) {
                    return new PlacementResult(existing.get(), false);
                }
                Instant now = clock.instant();
                Order order = Order.place(orderId, idempotencyKey, command.customerId(),
                        command.items().stream().map(OrderItem::from).toList(), now);
                orders.insert(order);
                outbox.enqueue(QueueNames.INVENTORY, OrderEvent.orderCreated(order.id(), order.customerId(),
                        order.lineItems(), order.totalAmount(), now));
                return new PlacementResult(order, true);
            });
        } catch (DataIntegrityViolationException e) {
            // A concurrent request inserted the same order id first.
            return orders.findById(orderId).map(order -> new PlacementResult(order, false)).orElseThrow(() -> e);
        }
        if (result.created()) {
            metrics.orderCreated();
        }
        return result;
    }
}
