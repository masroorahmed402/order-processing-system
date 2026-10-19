package com.example.orders.common.messaging;

import com.example.orders.common.event.MalformedEventException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    @Test
    void permanentExceptionsAreDetectedAnywhereInTheCauseChain() {
        assertThat(classifier.isPermanent(new MalformedEventException("bad", null))).isTrue();
        assertThat(classifier.isPermanent(new CompletionException(
                new RuntimeException("wrapped", new PermanentMessageException("deep"))))).isTrue();
    }

    @Test
    void everythingElseIsTransient() {
        assertThat(classifier.isPermanent(new IllegalStateException("db down"))).isFalse();
        assertThat(classifier.isPermanent(new CompletionException(new RuntimeException("timeout")))).isFalse();
    }

    @Test
    void describeReportsTheRootCause() {
        String description = classifier.describe(new CompletionException(new IllegalStateException("db down")));
        assertThat(description).isEqualTo("IllegalStateException: db down");
    }

    @Test
    void describePrefersThePermanentExceptionOverItsRootCause() {
        String description = classifier.describe(new CompletionException(
                new MalformedEventException("Malformed order event: eventId is required", new NullPointerException("eventId"))));
        assertThat(description).isEqualTo("MalformedEventException: Malformed order event: eventId is required");
    }
}
