package com.example.orders.order.config;

import com.example.orders.order.idempotency.IdempotencyProperties;
import io.awspring.cloud.autoconfigure.core.AwsClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class OrderServiceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Every API request talks to DynamoDB; size the pool to the web thread pool, not the SDK default of 50. */
    @Bean
    AwsClientCustomizer<DynamoDbClientBuilder> dynamoDbHttpClient(
            @Value("${aws.dynamodb.max-connections:200}") int maxConnections) {
        return new AwsClientCustomizer<>() {
            @Override
            public SdkHttpClient.Builder<?> httpClientBuilder() {
                return ApacheHttpClient.builder().maxConnections(maxConnections);
            }
        };
    }
}
