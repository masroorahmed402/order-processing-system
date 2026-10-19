package com.example.orders.inventory.domain;

/** Outcome of a reservation attempt. */
public sealed interface ReserveResult {

    Reservation reservation();

    /** Stock was decremented by this call. */
    record Reserved(Reservation reservation) implements ReserveResult {
    }

    /** Not enough stock; nothing changed, a REJECTED marker was stored. */
    record Rejected(Reservation reservation) implements ReserveResult {
    }

    /** The order was already handled (duplicate delivery); here is what happened back then. */
    record AlreadyProcessed(Reservation reservation) implements ReserveResult {
    }
}
