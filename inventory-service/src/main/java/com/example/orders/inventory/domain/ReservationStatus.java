package com.example.orders.inventory.domain;

public enum ReservationStatus {
    /** Stock was decremented for the order. */
    RESERVED,
    /** Not enough stock; nothing was decremented. */
    REJECTED,
    /** Stock was given back (compensation), or a release arrived before any reservation. */
    RELEASED
}
