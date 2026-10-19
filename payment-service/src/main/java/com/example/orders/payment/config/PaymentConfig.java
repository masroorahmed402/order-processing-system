package com.example.orders.payment.config;

import com.example.orders.common.messaging.EventPublisher;
import com.example.orders.payment.application.PaymentService;
import com.example.orders.payment.domain.PaymentRepository;
import com.example.orders.payment.gateway.PaymentGateway;
import com.example.orders.payment.gateway.PaymentGatewayProperties;
import com.example.orders.payment.gateway.SimulatedPaymentGateway;
import com.example.orders.payment.persistence.DynamoDbPaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(PaymentGatewayProperties.class)
public class PaymentConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Swap this bean for a real processor adapter (Stripe, Adyen, ...) in production. */
    @Bean
    PaymentGateway paymentGateway(PaymentGatewayProperties properties) {
        return new SimulatedPaymentGateway(properties);
    }

    @Bean
    PaymentRepository paymentRepository(DynamoDbClient dynamo, @Value("${payment.table:payments}") String table) {
        return new DynamoDbPaymentRepository(dynamo, table);
    }

    @Bean
    PaymentService paymentService(PaymentRepository payments, PaymentGateway gateway, EventPublisher publisher,
                                  MeterRegistry metrics, Clock clock,
                                  @Value("${payment.claim-stale-after:20s}") Duration claimStaleAfter) {
        return new PaymentService(payments, gateway, publisher, metrics, clock, claimStaleAfter);
    }
}
