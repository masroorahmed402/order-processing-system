package com.example.orders.order.metrics;

import com.example.orders.order.domain.Order;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Business metrics for orders; exported to CloudWatch (namespace OrderProcessing). */
@Component
public class OrderMetrics {

    public static final String CREATED = "orders.created";
    public static final String TRANSITIONS = "orders.transitions";
    public static final String SAGA_DURATION = "orders.saga.duration";
    public static final String ANOMALIES = "orders.anomalies";

    private final MeterRegistry registry;
    private final Counter created;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.created = Counter.builder(CREATED).description("Orders accepted by the API").register(registry);
    }

    public void orderCreated() {
        created.increment();
    }

    /** Records a status change; terminal states also record end-to-end saga latency. */
    public void transitioned(Order order, Instant now) {
        Counter.builder(TRANSITIONS)
                .description("Order status changes")
                .tag("status", order.status().name())
                .register(registry)
                .increment();
        if (order.isTerminal()) {
            Timer.builder(SAGA_DURATION)
                    .description("Time from order creation to CONFIRMED/FAILED")
                    .tag("status", order.status().name())
                    .register(registry)
                    .record(Duration.between(order.createdAt(), now));
        }
    }

    public void anomaly(String type) {
        Counter.builder(ANOMALIES).description("Saga states that need manual attention")
                .tag("type", type).register(registry).increment();
    }
}
