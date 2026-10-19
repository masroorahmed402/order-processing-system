package com.example.orders.order.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param table          DynamoDB table holding the keys
 * @param ttl            how long a key is remembered (DynamoDB TTL)
 * @param inProgressWait how long a duplicate request waits for the original to finish before 409
 * @param pollInterval   how often it checks during that wait
 * @param staleAfter     an IN_PROGRESS claim older than this is taken over (original request died)
 */
@ConfigurationProperties(prefix = "idempotency")
public record IdempotencyProperties(String table, Duration ttl, Duration inProgressWait, Duration pollInterval,
                                    Duration staleAfter) {

    public IdempotencyProperties {
        table = table == null ? "idempotency_keys" : table;
        ttl = ttl == null ? Duration.ofHours(24) : ttl;
        inProgressWait = inProgressWait == null ? Duration.ofSeconds(3) : inProgressWait;
        pollInterval = pollInterval == null ? Duration.ofMillis(50) : pollInterval;
        staleAfter = staleAfter == null ? Duration.ofSeconds(30) : staleAfter;
    }
}
