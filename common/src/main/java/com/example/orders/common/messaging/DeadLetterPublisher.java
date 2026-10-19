package com.example.orders.common.messaging;

import io.awspring.cloud.sqs.operations.SqsTemplate;

import java.util.Map;

/** Moves a message that can never succeed directly into its queue's DLQ, with the reason attached. */
public class DeadLetterPublisher {

    public static final String SOURCE_QUEUE_HEADER = "x-source-queue";
    public static final String FAILURE_REASON_HEADER = "x-failure-reason";

    private final SqsTemplate sqsTemplate;

    public DeadLetterPublisher(SqsTemplate sqsTemplate) {
        this.sqsTemplate = sqsTemplate;
    }

    public void send(String deadLetterQueue, String body, String sourceQueue, String reason) {
        // SQS message attribute values are limited in size; keep the reason short.
        String trimmedReason = reason.length() > 500 ? reason.substring(0, 500) : reason;
        sqsTemplate.send(to -> to.queue(deadLetterQueue)
                .payload(body)
                .headers(Map.of(SOURCE_QUEUE_HEADER, sourceQueue, FAILURE_REASON_HEADER, trimmedReason)));
    }
}
