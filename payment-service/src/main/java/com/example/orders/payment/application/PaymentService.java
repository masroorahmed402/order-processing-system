package com.example.orders.payment.application;

import com.example.orders.common.QueueNames;
import com.example.orders.common.event.EventType;
import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.messaging.EventPublisher;
import com.example.orders.common.messaging.PermanentMessageException;
import com.example.orders.payment.domain.PaymentInProgressException;
import com.example.orders.payment.domain.PaymentRecord;
import com.example.orders.payment.domain.PaymentRepository;
import com.example.orders.payment.domain.PaymentStatus;
import com.example.orders.payment.gateway.ChargeRequest;
import com.example.orders.payment.gateway.ChargeResult;
import com.example.orders.payment.gateway.PaymentGateway;
import com.example.orders.payment.gateway.TransientPaymentException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Charges an order once its stock is reserved.
 *
 * <ol>
 *   <li>If a final payment record exists, this is a duplicate: re-publish the stored result.</li>
 *   <li>Otherwise claim the order with a conditional put. Only the claim owner calls the gateway,
 *   passing the order id as the gateway idempotency key.</li>
 *   <li>Store the result (conditional on still owning the claim) and publish it.</li>
 * </ol>
 * A transient gateway error deletes the claim and rethrows, so SQS retries with backoff.
 */
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final EventPublisher publisher;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Duration claimStaleAfter;

    public PaymentService(PaymentRepository payments, PaymentGateway gateway, EventPublisher publisher,
                          MeterRegistry metrics, Clock clock, Duration claimStaleAfter) {
        this.payments = payments;
        this.gateway = gateway;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
        this.claimStaleAfter = claimStaleAfter;
    }

    public void handle(OrderEvent event) {
        if (event.type() != EventType.INVENTORY_RESERVED) {
            throw new PermanentMessageException(event.type() + " does not belong on " + QueueNames.PAYMENT);
        }
        UUID orderId = event.orderId();
        Optional<PaymentRecord> existing = payments.find(orderId);
        if (existing.isPresent() && existing.get().isFinal()) {
            count("duplicate");
            log.info("Duplicate INVENTORY_RESERVED for order {}, re-publishing stored {}", orderId, existing.get().status());
            publish(existing.get());
            return;
        }

        String token = UUID.randomUUID().toString();
        Instant now = clock.instant();
        boolean owned;
        if (existing.isEmpty()) {
            owned = payments.claim(orderId, event.amount(), token, now);
        } else if (existing.get().isStaleClaim(now, claimStaleAfter)) {
            log.warn("Taking over stale payment claim for order {}", orderId);
            owned = payments.takeOver(orderId, existing.get().claimToken(), token, now);
        } else {
            throw new PaymentInProgressException(orderId);
        }
        if (!owned) {
            replayIfFinal(orderId);
            return;
        }
        charge(event, token);
    }

    private void charge(OrderEvent event, String token) {
        ChargeResult result;
        try {
            // The order id is the gateway idempotency key: a takeover after a crash cannot double charge.
            result = gateway.charge(new ChargeRequest(event.orderId().toString(), event.orderId(), event.amount()));
        } catch (TransientPaymentException e) {
            count("transient_failure");
            try {
                payments.releaseClaim(event.orderId(), token);
            } catch (RuntimeException releaseFailure) {
                log.warn("Could not release payment claim for order {}: {}", event.orderId(), releaseFailure.getMessage());
            }
            throw e;
        }
        Optional<PaymentRecord> stored = payments.complete(event.orderId(), token, result, clock.instant());
        if (stored.isEmpty()) {
            // Our claim was taken over while we were charging; the new owner reports the result.
            replayIfFinal(event.orderId());
            return;
        }
        count(stored.get().status() == PaymentStatus.COMPLETED ? "completed" : "declined");
        log.info("Payment for order {}: {}", event.orderId(), stored.get().status());
        publish(stored.get());
    }

    private void replayIfFinal(UUID orderId) {
        PaymentRecord current = payments.find(orderId).orElseThrow(() -> new PaymentInProgressException(orderId));
        if (!current.isFinal()) {
            throw new PaymentInProgressException(orderId);
        }
        count("duplicate");
        publish(current);
    }

    private void publish(PaymentRecord record) {
        Instant now = clock.instant();
        OrderEvent outcome = record.status() == PaymentStatus.COMPLETED
                ? OrderEvent.paymentCompleted(record.orderId(), record.amount(), record.transactionId(), now)
                : OrderEvent.paymentFailed(record.orderId(), record.amount(), record.reason(), now);
        publisher.publish(QueueNames.ORDER_EVENTS, outcome);
    }

    private void count(String outcome) {
        metrics.counter("payments.processed", "outcome", outcome).increment();
    }
}
