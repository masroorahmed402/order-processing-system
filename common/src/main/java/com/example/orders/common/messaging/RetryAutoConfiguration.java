package com.example.orders.common.messaging;

import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Retry policy for every {@code @SqsListener}. Spring Cloud AWS's default listener container
 * factory picks up the {@link io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler} bean.
 */
@AutoConfiguration(before = SqsAutoConfiguration.class, after = MessagingAutoConfiguration.class)
public class RetryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExponentialBackoff exponentialBackoff(
            @Value("${messaging.backoff.base-seconds:2}") int baseSeconds,
            @Value("${messaging.backoff.max-seconds:300}") int maxSeconds) {
        return new ExponentialBackoff(baseSeconds, maxSeconds);
    }

    @Bean
    @ConditionalOnMissingBean
    public FailureClassifier failureClassifier() {
        return new FailureClassifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterPublisher deadLetterPublisher(SqsTemplate sqsTemplate) {
        return new DeadLetterPublisher(sqsTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public BackoffErrorHandler backoffErrorHandler(ExponentialBackoff backoff, FailureClassifier classifier,
                                                   DeadLetterPublisher deadLetters, ConsumerMetrics metrics) {
        return new BackoffErrorHandler(backoff, classifier, deadLetters, metrics);
    }
}
