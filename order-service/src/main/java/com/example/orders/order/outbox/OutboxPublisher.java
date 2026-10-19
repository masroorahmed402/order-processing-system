package com.example.orders.order.outbox;

import com.example.orders.common.messaging.EventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relays committed outbox rows to SQS. Delivery is at-least-once: if the process dies after
 * sending but before the "published" update commits, the rows are sent again on the next run,
 * which every consumer tolerates because they are idempotent.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final EventPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int batchSize;
    private final Counter published;
    private final Counter failures;

    public OutboxPublisher(OutboxEventRepository repository, EventPublisher publisher,
                           TransactionTemplate transactionTemplate, Clock clock, MeterRegistry registry,
                           @Value("${outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.batchSize = batchSize;
        this.published = Counter.builder("outbox.published").description("Outbox events sent to SQS").register(registry);
        this.failures = Counter.builder("outbox.publish.failures").description("Failed outbox relay runs").register(registry);
        Gauge.builder("outbox.pending", repository, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet sent to SQS")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:100}")
    public void publishPending() {
        try {
            int sent;
            do {
                sent = publishBatch();
            } while (sent == batchSize);
        } catch (RuntimeException e) {
            // Rows stay unpublished and are retried on the next tick.
            failures.increment();
            log.warn("Outbox publish failed, will retry: {}", e.getMessage());
        }
    }

    /** Publishes one batch inside a transaction and returns how many events were sent. */
    public int publishBatch() {
        Integer sent = transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = repository.lockUnpublished(batchSize);
            if (batch.isEmpty()) {
                return 0;
            }
            Map<String, List<OutboxEvent>> byDestination = new LinkedHashMap<>();
            for (OutboxEvent event : batch) {
                byDestination.computeIfAbsent(event.destination(), d -> new ArrayList<>()).add(event);
            }
            byDestination.forEach((destination, events) ->
                    publisher.publishRaw(destination, events.stream().map(OutboxEvent::payload).toList()));
            var now = clock.instant();
            batch.forEach(event -> event.markPublished(now));
            return batch.size();
        });
        int count = sent == null ? 0 : sent;
        published.increment(count);
        return count;
    }
}
