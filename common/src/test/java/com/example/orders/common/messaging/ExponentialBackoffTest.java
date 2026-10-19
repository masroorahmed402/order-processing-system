package com.example.orders.common.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExponentialBackoffTest {

    private final ExponentialBackoff backoff = new ExponentialBackoff(2, 300);

    @ParameterizedTest(name = "receive #{0} -> {1}s")
    @CsvSource({
            "1, 2",
            "2, 4",
            "3, 8",
            "4, 16",
            "5, 32",
            "6, 64",
            "7, 128",
            "8, 256",
            "9, 300",
            "10, 300",
    })
    void doublesPerAttemptAndCapsAtMax(int receiveCount, int expectedSeconds) {
        assertThat(backoff.visibilityTimeoutSeconds(receiveCount)).isEqualTo(expectedSeconds);
    }

    @Test
    void missingOrInvalidReceiveCountIsTreatedAsFirstAttempt() {
        assertThat(backoff.visibilityTimeoutSeconds(0)).isEqualTo(2);
        assertThat(backoff.visibilityTimeoutSeconds(-5)).isEqualTo(2);
    }

    @Test
    void hugeReceiveCountsDoNotOverflow() {
        assertThat(backoff.visibilityTimeoutSeconds(31)).isEqualTo(300);
        assertThat(backoff.visibilityTimeoutSeconds(64)).isEqualTo(300);
        assertThat(backoff.visibilityTimeoutSeconds(Integer.MAX_VALUE)).isEqualTo(300);
    }

    @Test
    void delaysAreStrictlyIncreasingUntilTheCap() {
        int previous = 0;
        for (int attempt = 1; attempt <= 8; attempt++) {
            int delay = backoff.visibilityTimeoutSeconds(attempt);
            assertThat(delay).isGreaterThan(previous);
            previous = delay;
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new ExponentialBackoff(0, 300)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoff(10, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoff(1, 50_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesConfiguration() {
        assertThat(backoff.baseSeconds()).isEqualTo(2);
        assertThat(backoff.maxSeconds()).isEqualTo(300);
    }
}
