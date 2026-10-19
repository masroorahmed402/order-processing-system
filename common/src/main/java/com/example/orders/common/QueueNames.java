package com.example.orders.common;

/**
 * Names of the SQS queues that make up the saga. Every work queue has a dead-letter queue named
 * {@code <queue>-dlq} (see infra/localstack/init-aws.sh and infra/terraform/sqs.tf).
 */
public final class QueueNames {

    /** ORDER_CREATED and INVENTORY_RELEASE, consumed by inventory-service. */
    public static final String INVENTORY = "inventory-queue";
    /** INVENTORY_RESERVED, consumed by payment-service. */
    public static final String PAYMENT = "payment-queue";
    /** Saga outcomes (INVENTORY_*, PAYMENT_*), consumed by order-service. */
    public static final String ORDER_EVENTS = "order-events-queue";

    public static final String DLQ_SUFFIX = "-dlq";

    private QueueNames() {
    }

    public static String deadLetterQueueOf(String queueName) {
        return queueName + DLQ_SUFFIX;
    }
}
