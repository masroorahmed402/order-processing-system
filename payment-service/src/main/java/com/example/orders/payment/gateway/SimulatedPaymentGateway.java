package com.example.orders.payment.gateway;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

/**
 * Stand-in for a real card processor. Declines amounts above a limit, fails transiently at a
 * configurable rate, and is idempotent per key like a real processor. It also counts real
 * charges so tests can prove an order was never charged twice.
 */
public class SimulatedPaymentGateway implements PaymentGateway {

    private final PaymentGatewayProperties properties;
    private final DoubleSupplier random;
    private final Map<String, ChargeResult> resultsByKey = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> chargesByOrder = new ConcurrentHashMap<>();

    public SimulatedPaymentGateway(PaymentGatewayProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    public SimulatedPaymentGateway(PaymentGatewayProperties properties, DoubleSupplier random) {
        this.properties = properties;
        this.random = random;
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        if (random.getAsDouble() < properties.getTransientFailureRate()) {
            throw new TransientPaymentException("Simulated processor timeout for order " + request.orderId());
        }
        return resultsByKey.computeIfAbsent(request.idempotencyKey(), key -> process(request));
    }

    private ChargeResult process(ChargeRequest request) {
        if (request.amount().compareTo(properties.getDeclineAbove()) > 0) {
            return new ChargeResult.Declined("amount " + request.amount().toPlainString()
                    + " exceeds limit " + properties.getDeclineAbove().toPlainString());
        }
        chargesByOrder.computeIfAbsent(request.orderId(), id -> new AtomicInteger()).incrementAndGet();
        return new ChargeResult.Approved("sim_" + UUID.randomUUID());
    }

    /** How many times money was actually taken for the order (must never exceed 1). */
    public int chargeCount(UUID orderId) {
        AtomicInteger count = chargesByOrder.get(orderId);
        return count == null ? 0 : count.get();
    }
}
