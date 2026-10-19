package com.example.orders.common.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Metrics shared by every SQS consumer: processing latency and failures, tagged by queue. */
public class ConsumerMetrics {

    public static final String PROCESSING_LATENCY = "consumer.processing.latency";
    public static final String FAILURES = "consumer.failures";

    private final MeterRegistry registry;

    public ConsumerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Runs the handler and records how long it took, successful or not. */
    public void timed(String queue, Runnable handler) {
        Timer.builder(PROCESSING_LATENCY)
                .description("Time spent handling one SQS message")
                .tag("queue", queue)
                .register(registry)
                .record(handler);
    }

    public void failure(String queue, FailureKind kind) {
        Counter.builder(FAILURES)
                .description("SQS messages whose handling threw an exception")
                .tag("queue", queue)
                .tag("kind", kind.tag())
                .register(registry)
                .increment();
    }

    public enum FailureKind {
        TRANSIENT, PERMANENT;

        String tag() {
            return name().toLowerCase();
        }
    }
}
