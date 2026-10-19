package com.example.orders.inventory.persistence;

import com.example.orders.common.event.LineItem;
import com.example.orders.inventory.domain.InventoryRepository;
import com.example.orders.inventory.domain.ReleaseResult;
import com.example.orders.inventory.domain.Reservation;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.ReserveResult;
import com.example.orders.inventory.domain.StockContentionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tables: {@code inventory} (pk {@code sku}, attribute {@code stock}) and
 * {@code inventory_reservations} (pk {@code orderId}).
 *
 * <p>A reservation is one {@code TransactWriteItems} call: put the reservation marker with
 * {@code attribute_not_exists(orderId)} and, for each SKU, decrement stock with
 * {@code stock >= :qty}. Either every write happens or none, so a duplicate delivery can never
 * decrement twice and a partially available order never takes some of the stock.
 */
public class DynamoDbInventoryRepository implements InventoryRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbInventoryRepository.class);
    private static final String TRANSACTION_CONFLICT = "TransactionConflict";

    private final DynamoDbClient dynamo;
    private final String stockTable;
    private final String reservationTable;
    private final int maxAttempts;

    public DynamoDbInventoryRepository(DynamoDbClient dynamo, String stockTable, String reservationTable, int maxAttempts) {
        this.dynamo = dynamo;
        this.stockTable = stockTable;
        this.reservationTable = reservationTable;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public ReserveResult reserve(UUID orderId, String customerId, BigDecimal amount, List<LineItem> items, Instant now) {
        Reservation reservation = Reservation.reserved(orderId, customerId, amount, items, now);
        Map<String, Integer> quantities = reservation.quantitiesBySku();

        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(TransactWriteItem.builder().put(p -> p
                .tableName(reservationTable)
                .item(ReservationMapper.toItem(reservation))
                .conditionExpression("attribute_not_exists(orderId)")).build());
        quantities.forEach((sku, qty) -> writes.add(TransactWriteItem.builder().update(u -> u
                .tableName(stockTable)
                .key(Map.of("sku", AttributeValue.fromS(sku)))
                .updateExpression("SET stock = stock - :qty")
                .conditionExpression("attribute_exists(sku) AND stock >= :qty")
                .expressionAttributeValues(Map.of(":qty", AttributeValue.fromN(qty.toString())))).build()));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                dynamo.transactWriteItems(r -> r.transactItems(writes));
                return new ReserveResult.Reserved(reservation);
            } catch (TransactionCanceledException e) {
                Optional<Reservation> existing = findReservation(orderId);
                if (existing.isPresent()) {
                    return new ReserveResult.AlreadyProcessed(existing.get());
                }
                if (isConflict(e)) {
                    pause(attempt);
                    continue;
                }
                Optional<String> shortSku = firstInsufficient(quantities);
                if (shortSku.isPresent()) {
                    return reject(reservation.rejected("insufficient stock for " + shortSku.get()));
                }
                // A stock condition failed but stock now looks sufficient: we raced a release. Try again.
                pause(attempt);
            }
        }
        throw new StockContentionException(orderId, maxAttempts);
    }

    private ReserveResult reject(Reservation rejected) {
        if (putIfAbsent(rejected)) {
            return new ReserveResult.Rejected(rejected);
        }
        // A concurrent delivery of the same order recorded its outcome first; report that one.
        return new ReserveResult.AlreadyProcessed(findReservation(rejected.orderId()).orElseThrow());
    }

    @Override
    public ReleaseResult release(UUID orderId, Instant now) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Optional<Reservation> existing = findReservation(orderId);
            if (existing.isEmpty()) {
                if (putIfAbsent(Reservation.releaseTombstone(orderId, now))) {
                    return ReleaseResult.NOTHING_RESERVED;
                }
                continue; // a reservation appeared concurrently; release it
            }
            Reservation reservation = existing.get();
            if (reservation.status() == ReservationStatus.RELEASED) {
                return ReleaseResult.ALREADY_RELEASED;
            }
            if (reservation.status() == ReservationStatus.REJECTED) {
                return ReleaseResult.NOTHING_RESERVED;
            }

            List<TransactWriteItem> writes = new ArrayList<>();
            writes.add(TransactWriteItem.builder().update(u -> u
                    .tableName(reservationTable)
                    .key(Map.of("orderId", AttributeValue.fromS(orderId.toString())))
                    .updateExpression("SET #status = :released, updatedAt = :now")
                    .conditionExpression("#status = :reserved")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":released", AttributeValue.fromS(ReservationStatus.RELEASED.name()),
                            ":reserved", AttributeValue.fromS(ReservationStatus.RESERVED.name()),
                            ":now", AttributeValue.fromS(now.toString())))).build());
            reservation.quantitiesBySku().forEach((sku, qty) -> writes.add(TransactWriteItem.builder().update(u -> u
                    .tableName(stockTable)
                    .key(Map.of("sku", AttributeValue.fromS(sku)))
                    .updateExpression("SET stock = stock + :qty")
                    .conditionExpression("attribute_exists(sku)")
                    .expressionAttributeValues(Map.of(":qty", AttributeValue.fromN(qty.toString())))).build()));
            try {
                dynamo.transactWriteItems(r -> r.transactItems(writes));
                return ReleaseResult.RELEASED;
            } catch (TransactionCanceledException e) {
                // Either a duplicate release won the race (next loop sees RELEASED) or we conflicted.
                pause(attempt);
            }
        }
        throw new StockContentionException(orderId, maxAttempts);
    }

    @Override
    public Optional<Reservation> findReservation(UUID orderId) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(reservationTable)
                .key(Map.of("orderId", AttributeValue.fromS(orderId.toString())))
                .consistentRead(true)).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(ReservationMapper.fromItem(item));
    }

    @Override
    public OptionalInt stockOf(String sku) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(stockTable)
                .key(Map.of("sku", AttributeValue.fromS(sku)))
                .consistentRead(true)).item();
        return item == null || item.isEmpty() ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(item.get("stock").n()));
    }

    private boolean putIfAbsent(Reservation reservation) {
        try {
            dynamo.putItem(r -> r.tableName(reservationTable)
                    .item(ReservationMapper.toItem(reservation))
                    .conditionExpression("attribute_not_exists(orderId)"));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private Optional<String> firstInsufficient(Map<String, Integer> quantities) {
        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            OptionalInt stock = stockOf(entry.getKey());
            if (stock.isEmpty() || stock.getAsInt() < entry.getValue()) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    private static boolean isConflict(TransactionCanceledException e) {
        if (e.hasCancellationReasons()) {
            return e.cancellationReasons().stream().map(CancellationReason::code).anyMatch(TRANSACTION_CONFLICT::equals);
        }
        return e.getMessage() != null && e.getMessage().contains(TRANSACTION_CONFLICT);
    }

    /** Jittered pause so transactions contending for a hot SKU spread out instead of colliding again. */
    private static void pause(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * attempt + 6));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (attempt > 3) {
            log.debug("Stock contention, attempt {}", attempt);
        }
    }

    /** DynamoDB item <-> {@link Reservation}. */
    static final class ReservationMapper {

        private ReservationMapper() {
        }

        static Map<String, AttributeValue> toItem(Reservation r) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("orderId", AttributeValue.fromS(r.orderId().toString()));
            item.put("status", AttributeValue.fromS(r.status().name()));
            item.put("updatedAt", AttributeValue.fromS(r.updatedAt().toString()));
            if (r.customerId() != null) {
                item.put("customerId", AttributeValue.fromS(r.customerId()));
            }
            if (r.amount() != null) {
                item.put("amount", AttributeValue.fromN(r.amount().toPlainString()));
            }
            if (r.reason() != null) {
                item.put("reason", AttributeValue.fromS(r.reason()));
            }
            item.put("items", AttributeValue.fromL(r.items().stream().map(i -> AttributeValue.fromM(Map.of(
                    "sku", AttributeValue.fromS(i.sku()),
                    "quantity", AttributeValue.fromN(Integer.toString(i.quantity())),
                    "unitPrice", AttributeValue.fromN(i.unitPrice().toPlainString())))).toList()));
            return item;
        }

        static Reservation fromItem(Map<String, AttributeValue> item) {
            List<LineItem> items = item.containsKey("items")
                    ? item.get("items").l().stream().map(AttributeValue::m).map(m -> new LineItem(
                    m.get("sku").s(), Integer.parseInt(m.get("quantity").n()), new BigDecimal(m.get("unitPrice").n()))).toList()
                    : List.of();
            return new Reservation(
                    UUID.fromString(item.get("orderId").s()),
                    ReservationStatus.valueOf(item.get("status").s()),
                    item.containsKey("customerId") ? item.get("customerId").s() : null,
                    item.containsKey("amount") ? new BigDecimal(item.get("amount").n()) : null,
                    items,
                    item.containsKey("reason") ? item.get("reason").s() : null,
                    Instant.parse(item.get("updatedAt").s()));
        }
    }
}
