package com.example.orders.common.messaging;

import com.example.orders.common.event.MalformedEventException;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.Visibility;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BackoffErrorHandlerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DeadLetterPublisher deadLetters = mock(DeadLetterPublisher.class);
    private final Visibility visibility = mock(Visibility.class);
    private final BackoffErrorHandler handler = new BackoffErrorHandler(
            new ExponentialBackoff(2, 300), new FailureClassifier(), deadLetters, new ConsumerMetrics(registry));

    @Test
    void transientFailureBacksOffByReceiveCountAndRethrows() {
        RuntimeException failure = new IllegalStateException("gateway timeout");

        assertThatThrownBy(() -> handler.handle(message("body", "3"), failure)).isSameAs(failure);

        verify(visibility).changeTo(8);
        verify(deadLetters, never()).send(anyString(), anyString(), anyString(), anyString());
        assertThat(registry.get(ConsumerMetrics.FAILURES).tag("queue", "payment-queue").tag("kind", "transient")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void firstFailureUsesBaseDelayWhenReceiveCountHeaderIsMissing() {
        Message<Object> message = MessageBuilder.<Object>withPayload("body")
                .setHeader(SqsHeaders.SQS_QUEUE_NAME_HEADER, "payment-queue")
                .setHeader(SqsHeaders.SQS_VISIBILITY_TIMEOUT_HEADER, visibility)
                .build();

        assertThatThrownBy(() -> handler.handle(message, new RuntimeException("boom")));

        verify(visibility).changeTo(2);
    }

    @Test
    void checkedExceptionsAreWrappedSoTheMessageIsStillNotAcknowledged() {
        assertThatThrownBy(() -> handler.handle(message("body", "1"), new Exception("checked")))
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void failingToChangeVisibilityStillRethrows() {
        doThrow(new RuntimeException("sqs unavailable")).when(visibility).changeTo(anyInt());
        RuntimeException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> handler.handle(message("body", "2"), failure)).isSameAs(failure);
    }

    @Test
    void permanentFailureGoesStraightToTheDlqAndIsAcknowledged() {
        handler.handle(message("{not json", "1"), new MalformedEventException("Malformed order event", null));

        verify(deadLetters).send(eq("payment-queue-dlq"), eq("{not json"), eq("payment-queue"),
                startsWith("MalformedEventException"));
        verify(visibility, never()).changeTo(anyInt());
        assertThat(registry.get(ConsumerMetrics.FAILURES).tag("kind", "permanent").counter().count()).isEqualTo(1.0);
    }

    @Test
    void receiveCountParsing() {
        assertThat(BackoffErrorHandler.receiveCount(message("b", "4"))).isEqualTo(4);
        assertThat(BackoffErrorHandler.receiveCount(message("b", "not-a-number"))).isEqualTo(1);
    }

    private Message<Object> message(String body, String receiveCount) {
        return MessageBuilder.<Object>withPayload(body)
                .setHeader(SqsHeaders.SQS_QUEUE_NAME_HEADER, "payment-queue")
                .setHeader(SqsHeaders.SQS_VISIBILITY_TIMEOUT_HEADER, visibility)
                .setHeader(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT, receiveCount)
                .build();
    }

}
