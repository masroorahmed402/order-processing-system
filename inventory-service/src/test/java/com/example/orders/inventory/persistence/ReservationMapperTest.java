package com.example.orders.inventory.persistence;

import com.example.orders.common.event.LineItem;
import com.example.orders.inventory.domain.Reservation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationMapperTest {

    @Test
    void roundTripsAFullReservation() {
        Reservation reservation = Reservation.reserved(UUID.randomUUID(), "c-1", new BigDecimal("12.50"),
                List.of(new LineItem("SKU-001", 2, new BigDecimal("6.25"))), Instant.parse("2026-10-19T10:00:00Z"));

        Reservation back = DynamoDbInventoryRepository.ReservationMapper.fromItem(
                DynamoDbInventoryRepository.ReservationMapper.toItem(reservation));

        assertThat(back.orderId()).isEqualTo(reservation.orderId());
        assertThat(back.status()).isEqualTo(reservation.status());
        assertThat(back.amount()).isEqualByComparingTo("12.50");
        assertThat(back.items()).hasSize(1);
        assertThat(back.items().get(0).unitPrice()).isEqualByComparingTo("6.25");
        assertThat(back.updatedAt()).isEqualTo(reservation.updatedAt());
    }

    @Test
    void roundTripsATombstoneWithoutOptionalFields() {
        Reservation tombstone = Reservation.releaseTombstone(UUID.randomUUID(), Instant.parse("2026-10-19T10:00:00Z"));

        Reservation back = DynamoDbInventoryRepository.ReservationMapper.fromItem(
                DynamoDbInventoryRepository.ReservationMapper.toItem(tombstone));

        assertThat(back.customerId()).isNull();
        assertThat(back.amount()).isNull();
        assertThat(back.items()).isEmpty();
        assertThat(back.reason()).isEqualTo(tombstone.reason());
    }
}
