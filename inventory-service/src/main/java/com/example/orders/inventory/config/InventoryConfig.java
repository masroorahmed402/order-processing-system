package com.example.orders.inventory.config;

import com.example.orders.inventory.domain.InventoryRepository;
import com.example.orders.inventory.persistence.DynamoDbInventoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Clock;

@Configuration
public class InventoryConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    InventoryRepository inventoryRepository(DynamoDbClient dynamo,
                                            @Value("${inventory.stock-table:inventory}") String stockTable,
                                            @Value("${inventory.reservation-table:inventory_reservations}") String reservationTable,
                                            @Value("${inventory.max-transaction-attempts:10}") int maxAttempts) {
        return new DynamoDbInventoryRepository(dynamo, stockTable, reservationTable, maxAttempts);
    }
}
