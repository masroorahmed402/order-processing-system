package com.example.orders.inventory.domain;

public enum ReleaseResult {
    /** Stock was given back by this call. */
    RELEASED,
    /** A previous delivery already gave the stock back. */
    ALREADY_RELEASED,
    /** Nothing was reserved for the order (rejected, or not seen yet); stock untouched. */
    NOTHING_RESERVED
}
