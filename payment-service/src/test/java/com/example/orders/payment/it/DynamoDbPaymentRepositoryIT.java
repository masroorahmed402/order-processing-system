package com.example.orders.payment.it;

import com.example.orders.common.testsupport.AwsTestClients;
import com.example.orders.payment.domain.PaymentRecord;
import com.example.orders.payment.domain.PaymentStatus;
import com.example.orders.payment.gateway.ChargeResult;
import com.example.orders.payment.persistence.DynamoDbPaymentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbPaymentRepositoryIT {

    private final DynamoDbPaymentRepository repository = new DynamoDbPaymentRepository(AwsTestClients.dynamo(), "payments");
    private final Instant now = Instant.parse("2026-10-19T10:00:00Z");

    @Test
    void onlyOneClaimPerOrder() {
        UUID orderId = UUID.randomUUID();

        assertThat(repository.claim(orderId, new BigDecimal("12.34"), "a", now)).isTrue();
        assertThat(repository.claim(orderId, new BigDecimal("12.34"), "b", now)).isFalse();

        PaymentRecord record = repository.find(orderId).orElseThrow();
        assertThat(record.status()).isEqualTo(PaymentStatus.CHARGING);
        assertThat(record.claimToken()).isEqualTo("a");
        assertThat(record.amount()).isEqualByComparingTo("12.34");
    }

    @Test
    void onlyTheClaimOwnerCanComplete() {
        UUID orderId = UUID.randomUUID();
        repository.claim(orderId, BigDecimal.TEN, "owner", now);

        assertThat(repository.complete(orderId, "intruder", new ChargeResult.Approved("tx-x"), now)).isEmpty();
        PaymentRecord done = repository.complete(orderId, "owner", new ChargeResult.Approved("tx-1"), now.plusSeconds(1)).orElseThrow();

        assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(done.transactionId()).isEqualTo("tx-1");
        assertThat(repository.complete(orderId, "owner", new ChargeResult.Approved("tx-2"), now)).isEmpty();
        assertThat(repository.find(orderId).orElseThrow().transactionId()).isEqualTo("tx-1");
    }

    @Test
    void declineIsStoredWithItsReason() {
        UUID orderId = UUID.randomUUID();
        repository.claim(orderId, BigDecimal.TEN, "owner", now);

        PaymentRecord declined = repository.complete(orderId, "owner", new ChargeResult.Declined("limit"), now).orElseThrow();

        assertThat(declined.status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(declined.reason()).isEqualTo("limit");
    }

    @Test
    void releasedClaimCanBeClaimedAgainButOnlyByItsOwner() {
        UUID orderId = UUID.randomUUID();
        repository.claim(orderId, BigDecimal.TEN, "owner", now);

        repository.releaseClaim(orderId, "someone-else");
        assertThat(repository.find(orderId)).isPresent();

        repository.releaseClaim(orderId, "owner");
        assertThat(repository.find(orderId)).isEmpty();
        assertThat(repository.claim(orderId, BigDecimal.TEN, "retry", now)).isTrue();
    }

    @Test
    void takeOverRequiresTheStaleToken() {
        UUID orderId = UUID.randomUUID();
        repository.claim(orderId, BigDecimal.TEN, "dead", now);

        assertThat(repository.takeOver(orderId, "wrong", "new", now)).isFalse();
        assertThat(repository.takeOver(orderId, "dead", "new", now)).isTrue();
        assertThat(repository.find(orderId).orElseThrow().claimToken()).isEqualTo("new");
        assertThat(repository.complete(orderId, "dead", new ChargeResult.Approved("tx"), now)).isEmpty();
    }
}
