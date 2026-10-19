package com.example.orders.payment.persistence;

import com.example.orders.payment.domain.PaymentRecord;
import com.example.orders.payment.domain.PaymentRepository;
import com.example.orders.payment.domain.PaymentStatus;
import com.example.orders.payment.gateway.ChargeResult;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code payments} table, pk {@code orderId}. The claim is a conditional put
 * ({@code attribute_not_exists(orderId)}), so at most one consumer can ever be charging an order.
 */
public class DynamoDbPaymentRepository implements PaymentRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbPaymentRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public Optional<PaymentRecord> find(UUID orderId) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).key(key(orderId)).consistentRead(true)).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
    }

    @Override
    public boolean claim(UUID orderId, BigDecimal amount, String claimToken, Instant now) {
        Map<String, AttributeValue> item = new HashMap<>(key(orderId));
        item.put("status", AttributeValue.fromS(PaymentStatus.CHARGING.name()));
        item.put("amount", AttributeValue.fromN(amount.toPlainString()));
        item.put("claimToken", AttributeValue.fromS(claimToken));
        item.put("claimedAt", AttributeValue.fromS(now.toString()));
        item.put("updatedAt", AttributeValue.fromS(now.toString()));
        try {
            dynamo.putItem(r -> r.tableName(table).item(item).conditionExpression("attribute_not_exists(orderId)"));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public boolean takeOver(UUID orderId, String staleToken, String newToken, Instant now) {
        try {
            dynamo.updateItem(r -> r.tableName(table).key(key(orderId))
                    .updateExpression("SET claimToken = :new, claimedAt = :now, updatedAt = :now")
                    .conditionExpression("#status = :charging AND claimToken = :old")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":new", AttributeValue.fromS(newToken),
                            ":old", AttributeValue.fromS(staleToken),
                            ":now", AttributeValue.fromS(now.toString()),
                            ":charging", AttributeValue.fromS(PaymentStatus.CHARGING.name()))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public Optional<PaymentRecord> complete(UUID orderId, String claimToken, ChargeResult result, Instant now) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":token", AttributeValue.fromS(claimToken));
        values.put(":charging", AttributeValue.fromS(PaymentStatus.CHARGING.name()));
        values.put(":now", AttributeValue.fromS(now.toString()));
        String update;
        switch (result) {
            case ChargeResult.Approved approved -> {
                update = "SET #status = :status, transactionId = :detail, updatedAt = :now";
                values.put(":status", AttributeValue.fromS(PaymentStatus.COMPLETED.name()));
                values.put(":detail", AttributeValue.fromS(approved.transactionId()));
            }
            case ChargeResult.Declined declined -> {
                update = "SET #status = :status, reason = :detail, updatedAt = :now";
                values.put(":status", AttributeValue.fromS(PaymentStatus.DECLINED.name()));
                values.put(":detail", AttributeValue.fromS(declined.reason()));
            }
        }
        try {
            Map<String, AttributeValue> updated = dynamo.updateItem(r -> r.tableName(table).key(key(orderId))
                    .updateExpression(update)
                    .conditionExpression("#status = :charging AND claimToken = :token")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(values)
                    .returnValues(ReturnValue.ALL_NEW)).attributes();
            return Optional.of(fromItem(updated));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    @Override
    public void releaseClaim(UUID orderId, String claimToken) {
        try {
            dynamo.deleteItem(r -> r.tableName(table).key(key(orderId))
                    .conditionExpression("#status = :charging AND claimToken = :token")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":charging", AttributeValue.fromS(PaymentStatus.CHARGING.name()),
                            ":token", AttributeValue.fromS(claimToken))));
        } catch (ConditionalCheckFailedException e) {
            // Someone else owns the record now; leave it alone.
        }
    }

    private static Map<String, AttributeValue> key(UUID orderId) {
        return Map.of("orderId", AttributeValue.fromS(orderId.toString()));
    }

    static PaymentRecord fromItem(Map<String, AttributeValue> item) {
        return new PaymentRecord(
                UUID.fromString(item.get("orderId").s()),
                PaymentStatus.valueOf(item.get("status").s()),
                new BigDecimal(item.get("amount").n()),
                text(item, "transactionId"),
                text(item, "reason"),
                text(item, "claimToken"),
                Instant.parse(item.get("claimedAt").s()),
                Instant.parse(item.get("updatedAt").s()));
    }

    private static String text(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }
}
