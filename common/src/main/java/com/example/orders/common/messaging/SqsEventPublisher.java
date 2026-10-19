package com.example.orders.common.messaging;

import com.example.orders.common.event.OrderEvent;
import com.example.orders.common.event.OrderEventCodec;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.List;

/** {@link EventPublisher} on top of Spring Cloud AWS's {@link SqsTemplate}. */
public class SqsEventPublisher implements EventPublisher {

    /** SendMessageBatch accepts at most 10 entries. */
    static final int SQS_BATCH_LIMIT = 10;

    private final SqsTemplate sqsTemplate;
    private final OrderEventCodec codec;

    public SqsEventPublisher(SqsTemplate sqsTemplate, OrderEventCodec codec) {
        this.sqsTemplate = sqsTemplate;
        this.codec = codec;
    }

    @Override
    public void publish(String queue, OrderEvent event) {
        sqsTemplate.send(to -> to.queue(queue).payload(codec.encode(event)));
    }

    @Override
    public void publishRaw(String queue, List<String> bodies) {
        for (int from = 0; from < bodies.size(); from += SQS_BATCH_LIMIT) {
            List<Message<String>> chunk = bodies.subList(from, Math.min(from + SQS_BATCH_LIMIT, bodies.size()))
                    .stream()
                    .map(body -> MessageBuilder.withPayload(body).build())
                    .toList();
            SendResult.Batch<String> result = sqsTemplate.sendMany(queue, chunk);
            if (!result.failed().isEmpty()) {
                throw new IllegalStateException(result.failed().size() + " of " + chunk.size()
                        + " messages could not be sent to " + queue);
            }
        }
    }
}
