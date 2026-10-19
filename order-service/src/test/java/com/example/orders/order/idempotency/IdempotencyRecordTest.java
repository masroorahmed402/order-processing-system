package com.example.orders.order.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyRecordTest {

    private final Instant created = Instant.parse("2026-10-19T10:00:00Z");

    @Test
    void inProgressClaimBecomesStaleAfterThreshold() {
        IdempotencyRecord record = new IdempotencyRecord("k", "fp", UUID.randomUUID(),
                IdempotencyRecord.Status.IN_PROGRESS, created);

        assertThat(record.isStale(created.plusSeconds(10), Duration.ofSeconds(30))).isFalse();
        assertThat(record.isStale(created.plusSeconds(31), Duration.ofSeconds(30))).isTrue();
        assertThat(record.isCompleted()).isFalse();
    }

    @Test
    void completedRecordIsNeverStale() {
        IdempotencyRecord record = new IdempotencyRecord("k", "fp", UUID.randomUUID(),
                IdempotencyRecord.Status.COMPLETED, created);

        assertThat(record.isStale(created.plusSeconds(3600), Duration.ofSeconds(30))).isFalse();
        assertThat(record.isCompleted()).isTrue();
    }

    @Test
    void propertiesHaveSensibleDefaults() {
        IdempotencyProperties properties = new IdempotencyProperties(null, null, null, null, null);

        assertThat(properties.table()).isEqualTo("idempotency_keys");
        assertThat(properties.ttl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.staleAfter()).isEqualTo(Duration.ofSeconds(30));
    }
}
