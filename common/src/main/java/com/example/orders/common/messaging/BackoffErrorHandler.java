package com.example.orders.common.messaging;

import com.example.orders.common.QueueNames;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.Visibility;
import io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;

import java.util.concurrent.CompletionException;

/**
 * Error handler installed on every {@code @SqsListener} container.
 *
 * <ul>
 *   <li><b>Transient failure</b> (anything not a {@link PermanentMessageException}): sets the
 *   message's visibility timeout to the {@link ExponentialBackoff} delay for its receive count and
 *   rethrows, so the message is <i>not</i> acknowledged. SQS redelivers it after the delay; after
 *   {@code maxReceiveCount} (5) receives the queue's RedrivePolicy moves it to the DLQ.</li>
 *   <li><b>Permanent failure</b>: copies the message to {@code <queue>-dlq} with the failure reason
 *   attached and returns normally, so the original is acknowledged (deleted) instead of being
 *   retried five times for nothing.</li>
 * </ul>
 */
public class BackoffErrorHandler implements ErrorHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(BackoffErrorHandler.class);

    private final ExponentialBackoff backoff;
    private final FailureClassifier classifier;
    private final DeadLetterPublisher deadLetters;
    private final ConsumerMetrics metrics;

    public BackoffErrorHandler(ExponentialBackoff backoff, FailureClassifier classifier,
                               DeadLetterPublisher deadLetters, ConsumerMetrics metrics) {
        this.backoff = backoff;
        this.classifier = classifier;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
    }

    @Override
    public void handle(Message<Object> message, Throwable failure) {
        String queue = header(message, SqsHeaders.SQS_QUEUE_NAME_HEADER, "unknown");
        String reason = classifier.describe(failure);

        if (classifier.isPermanent(failure)) {
            metrics.failure(queue, ConsumerMetrics.FailureKind.PERMANENT);
            String dlq = QueueNames.deadLetterQueueOf(queue);
            deadLetters.send(dlq, String.valueOf(message.getPayload()), queue, reason);
            log.error("Permanent failure on {} (message {}), moved to {}: {}",
                    queue, message.getHeaders().getId(), dlq, reason);
            return; // acknowledged: removed from the source queue
        }

        metrics.failure(queue, ConsumerMetrics.FailureKind.TRANSIENT);
        int receiveCount = receiveCount(message);
        int delaySeconds = backoff.visibilityTimeoutSeconds(receiveCount);
        Visibility visibility = message.getHeaders().get(SqsHeaders.SQS_VISIBILITY_TIMEOUT_HEADER, Visibility.class);
        if (visibility != null) {
            try {
                visibility.changeTo(delaySeconds);
            } catch (RuntimeException e) {
                // The message will still come back after the queue's default visibility timeout.
                log.warn("Could not change visibility of message on {}: {}", queue, e.getMessage());
            }
        }
        log.warn("Transient failure on {} (receive #{}), retrying in {}s: {}", queue, receiveCount, delaySeconds, reason);
        throw failure instanceof RuntimeException re ? re : new CompletionException(failure);
    }

    static int receiveCount(Message<?> message) {
        Object raw = message.getHeaders().get(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT);
        if (raw == null) {
            return 1;
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String header(Message<?> message, String name, String fallback) {
        Object value = message.getHeaders().get(name);
        return value == null ? fallback : value.toString();
    }
}
