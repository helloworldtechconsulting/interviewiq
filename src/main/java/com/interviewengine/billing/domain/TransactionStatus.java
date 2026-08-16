package com.interviewengine.billing.domain;

/**
 * Wallet transaction lifecycle states — V026 aligned values.
 * DB CHECK values: 'PENDING', 'CONFIRMED', 'RELEASED', 'FAILED'
 *
 * <p>RESERVATION transactions start as PENDING and move to CONFIRMED (settlement)
 * or RELEASED (cancellation). All other types are typically CONFIRMED on creation.
 */
public enum TransactionStatus {
    PENDING,
    CONFIRMED,
    RELEASED,
    FAILED
}
