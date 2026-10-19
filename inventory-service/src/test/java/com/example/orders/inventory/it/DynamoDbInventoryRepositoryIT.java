package com.example.orders.inventory.it;

import com.example.orders.common.event.LineItem;
import com.example.orders.common.testsupport.AwsTestClients;
import com.example.orders.inventory.domain.ReleaseResult;
import com.example.orders.inventory.domain.Reservation;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.ReserveResult;
import com.example.orders.inventory.persistence.DynamoDbInventoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the repository against DynamoDB in LocalStack (no Spring context needed). */
class DynamoDbInventoryRepositoryIT {

    private final DynamoDbInventoryRepository repository =
            new DynamoDbInventoryRepository(AwsTestClients.dynamo(), "inventory", "inventory_reservations", 10);
    private final Instant now = Instant.now();

    @Test
    void reserveDecrementsStockAndRecordsTheReservation() {
        String sku = sku(10);
        UUID orderId = UUID.randomUUID();

        ReserveResult result = repository.reserve(orderId, "c", BigDecimal.TEN, items(sku, 3), now);

        assertThat(result).isInstanceOf(ReserveResult.Reserved.class);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(7);
        assertThat(repository.findReservation(orderId)).get().extracting(Reservation::status).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void duplicateReservationDoesNotDecrementTwice() {
        String sku = sku(10);
        UUID orderId = UUID.randomUUID();
        repository.reserve(orderId, "c", BigDecimal.TEN, items(sku, 3), now);

        ReserveResult again = repository.reserve(orderId, "c", BigDecimal.TEN, items(sku, 3), now);

        assertThat(again).isInstanceOf(ReserveResult.AlreadyProcessed.class);
        assertThat(again.reservation().status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(7);
    }

    @Test
    void insufficientStockIsRejectedAndRemembered() {
        String sku = sku(2);
        UUID orderId = UUID.randomUUID();

        ReserveResult result = repository.reserve(orderId, "c", BigDecimal.TEN, items(sku, 5), now);

        assertThat(result).isInstanceOf(ReserveResult.Rejected.class);
        assertThat(result.reservation().reason()).contains(sku);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(2);
        // A redelivery after stock is replenished must not flip the decision.
        AwsTestClients.putStock(sku, 100);
        ReserveResult redelivered = repository.reserve(orderId, "c", BigDecimal.TEN, items(sku, 5), now);
        assertThat(redelivered).isInstanceOf(ReserveResult.AlreadyProcessed.class);
        assertThat(redelivered.reservation().status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(100);
    }

    @Test
    void multiItemReservationIsAllOrNothing() {
        String plenty = sku(50);
        String scarce = sku(1);
        UUID orderId = UUID.randomUUID();
        List<LineItem> items = List.of(new LineItem(plenty, 5, BigDecimal.ONE), new LineItem(scarce, 2, BigDecimal.ONE));

        ReserveResult result = repository.reserve(orderId, "c", BigDecimal.TEN, items, now);

        assertThat(result).isInstanceOf(ReserveResult.Rejected.class);
        assertThat(AwsTestClients.stockOf(plenty)).isEqualTo(50);
        assertThat(AwsTestClients.stockOf(scarce)).isEqualTo(1);
    }

    @Test
    void unknownSkuIsRejected() {
        ReserveResult result = repository.reserve(UUID.randomUUID(), "c", BigDecimal.TEN,
                items("NO-SUCH-SKU-" + UUID.randomUUID(), 1), now);

        assertThat(result).isInstanceOf(ReserveResult.Rejected.class);
    }

    @Test
    void sameSkuTwiceInOneOrderIsAggregated() {
        String sku = sku(10);
        List<LineItem> items = List.of(new LineItem(sku, 2, BigDecimal.ONE), new LineItem(sku, 3, BigDecimal.ONE));

        assertThat(repository.reserve(UUID.randomUUID(), "c", BigDecimal.TEN, items, now)).isInstanceOf(ReserveResult.Reserved.class);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(5);
    }

    @Test
    void releaseRestoresStockExactlyOnce() {
        String sku = sku(10);
        UUID orderId = UUID.randomUUID();
        repository.reserve(orderId, "c", BigDecimal.TEN, items(sku, 4), now);

        assertThat(repository.release(orderId, now)).isEqualTo(ReleaseResult.RELEASED);
        assertThat(repository.release(orderId, now)).isEqualTo(ReleaseResult.ALREADY_RELEASED);

        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(10);
        assertThat(repository.findReservation(orderId)).get().extracting(Reservation::status).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void releasingARejectedOrderRestoresNothing() {
        String sku = sku(1);
        UUID orderId = UUID.randomUUID();
        repository.reserve(orderId, "c", BigDecimal.TEN, items(sku, 5), now);

        assertThat(repository.release(orderId, now)).isEqualTo(ReleaseResult.NOTHING_RESERVED);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(1);
    }

    @Test
    void releaseBeforeReservationBlocksTheLateReservation() {
        String sku = sku(10);
        UUID orderId = UUID.randomUUID();

        assertThat(repository.release(orderId, now)).isEqualTo(ReleaseResult.NOTHING_RESERVED);
        ReserveResult late = repository.reserve(orderId, "c", BigDecimal.TEN, items(sku, 4), now);

        assertThat(late).isInstanceOf(ReserveResult.AlreadyProcessed.class);
        assertThat(late.reservation().status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(10);
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        String sku = sku(20);
        int orders = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<ReserveResult>> calls = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            calls.add(() -> repository.reserve(UUID.randomUUID(), "c", BigDecimal.ONE, items(sku, 1), Instant.now()));
        }

        int reserved = 0;
        int rejected = 0;
        for (Future<ReserveResult> f : pool.invokeAll(calls)) {
            ReserveResult r = f.get();
            if (r instanceof ReserveResult.Reserved) {
                reserved++;
            } else if (r instanceof ReserveResult.Rejected) {
                rejected++;
            }
        }
        pool.shutdown();

        assertThat(reserved).isEqualTo(20);
        assertThat(rejected).isEqualTo(30);
        assertThat(AwsTestClients.stockOf(sku)).isZero();
    }

    @Test
    void concurrentDuplicatesOfOneOrderDecrementOnce() throws Exception {
        String sku = sku(10);
        UUID orderId = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<ReserveResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> repository.reserve(orderId, "c", BigDecimal.ONE, items(sku, 2), Instant.now()));
        }

        long reservedCount = 0;
        for (Future<ReserveResult> f : pool.invokeAll(calls)) {
            if (f.get() instanceof ReserveResult.Reserved) {
                reservedCount++;
            }
        }
        pool.shutdown();

        assertThat(reservedCount).isEqualTo(1);
        assertThat(AwsTestClients.stockOf(sku)).isEqualTo(8);
    }

    @Test
    void stockLookup() {
        String sku = sku(42);
        assertThat(repository.stockOf(sku)).hasValue(42);
        assertThat(repository.stockOf("missing-" + UUID.randomUUID())).isEmpty();
    }

    private static String sku(int stock) {
        String sku = "IT-" + UUID.randomUUID();
        AwsTestClients.putStock(sku, stock);
        return sku;
    }

    private static List<LineItem> items(String sku, int qty) {
        return List.of(new LineItem(sku, qty, new BigDecimal("1.00")));
    }
}
