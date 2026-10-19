package com.example.orders.order.idempotency;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Keys live in the {@code idempotency_keys} table. The claim is a conditional put
 * ({@code attribute_not_exists(idempotencyKey)}), which DynamoDB evaluates atomically, so two
 * concurrent requests with the same key can never both win.
 */
@Component
class DynamoDbIdempotencyStore implements IdempotencyStore {

    private static final String KEY = "idempotencyKey";

    private final DynamoDbClient dynamo;
    private final IdempotencyProperties properties;

    DynamoDbIdempotencyStore(DynamoDbClient dynamo, IdempotencyProperties properties) {
        this.dynamo = dynamo;
        this.properties = properties;
    }

    @Override
    public ClaimResult claim(String key, String fingerprint, UUID orderId, Instant now) {
        Map<String, AttributeValue> item = Map.of(
                KEY, AttributeValue.fromS(key),
                "requestFingerprint", AttributeValue.fromS(fingerprint),
                "orderId", AttributeValue.fromS(orderId.toString()),
                "status", AttributeValue.fromS(IdempotencyRecord.Status.IN_PROGRESS.name()),
                "createdAt", AttributeValue.fromS(now.toString()),
                "expiresAt", AttributeValue.fromN(Long.toString(now.plus(properties.ttl()).getEpochSecond())));
        try {
            dynamo.putItem(r -> r.tableName(properties.table())
                    .item(item)
                    .conditionExpression("attribute_not_exists(" + KEY + ")")
                    .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD));
            return new ClaimResult.Claimed();
        } catch (ConditionalCheckFailedException e) {
            Map<String, AttributeValue> existing = e.hasItem() && !e.item().isEmpty() ? e.item() : read(key);
            if (existing.isEmpty()) {
                // Deleted (TTL) between our put and read: the key is free again.
                return claim(key, fingerprint, orderId, now);
            }
            return new ClaimResult.AlreadyClaimed(toRecord(existing));
        }
    }

    @Override
    public void markCompleted(String key, UUID orderId, Instant now) {
        try {
            dynamo.updateItem(r -> r.tableName(properties.table())
                    .key(Map.of(KEY, AttributeValue.fromS(key)))
                    .updateExpression("SET #status = :completed, completedAt = :now")
                    .conditionExpression("orderId = :orderId")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":completed", AttributeValue.fromS(IdempotencyRecord.Status.COMPLETED.name()),
                            ":now", AttributeValue.fromS(now.toString()),
                            ":orderId", AttributeValue.fromS(orderId.toString()))));
        } catch (ConditionalCheckFailedException e) {
            throw new IllegalStateException("Idempotency key " + key + " no longer maps to order " + orderId, e);
        }
    }

    private Map<String, AttributeValue> read(String key) {
        return dynamo.getItem(r -> r.tableName(properties.table())
                .key(Map.of(KEY, AttributeValue.fromS(key)))
                .consistentRead(true)).item();
    }

    private static IdempotencyRecord toRecord(Map<String, AttributeValue> item) {
        return new IdempotencyRecord(
                item.get(KEY).s(),
                item.get("requestFingerprint").s(),
                UUID.fromString(item.get("orderId").s()),
                IdempotencyRecord.Status.valueOf(item.get("status").s()),
                Instant.parse(item.get("createdAt").s()));
    }
}
