package com.example.orders.common.messaging;

import com.example.orders.common.event.OrderEventCodec;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.awspring.cloud.sqs.support.converter.MessagingMessageConverter;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.converter.StringMessageConverter;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Messaging beans shared by all services. Runs before Spring Cloud AWS's own auto-configuration
 * so that its {@code @ConditionalOnMissingBean} defaults back off in favour of these.
 */
@AutoConfiguration(before = SqsAutoConfiguration.class)
public class MessagingAutoConfiguration {

    /**
     * Bodies travel as plain JSON strings in both directions: no {@code JavaType} header is sent and
     * incoming payloads are never deserialized by the framework. Our own {@link OrderEventCodec}
     * decodes them, so a malformed body surfaces as a classifiable {@code MalformedEventException}
     * inside the listener rather than as a framework conversion error.
     */
    @Bean
    @ConditionalOnMissingBean
    public MessagingMessageConverter<Message> sqsMessagingMessageConverter() {
        SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
        converter.setPayloadMessageConverter(new StringMessageConverter());
        converter.setPayloadTypeMapper(message -> String.class);
        converter.doNotSendPayloadTypeHeader();
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    public SqsTemplate sqsTemplate(SqsAsyncClient sqsAsyncClient, MessagingMessageConverter<Message> converter) {
        return SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient)
                .messageConverter(converter)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public OrderEventCodec orderEventCodec() {
        return new OrderEventCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher(SqsTemplate sqsTemplate, OrderEventCodec codec) {
        return new SqsEventPublisher(sqsTemplate, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumerMetrics consumerMetrics(MeterRegistry meterRegistry) {
        return new ConsumerMetrics(meterRegistry);
    }
}
