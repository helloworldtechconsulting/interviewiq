package com.interviewiq.billing.domain;

/**
 * Wallet transaction types — V026 aligned values.
 * DB CHECK values: 'TOPUP', 'RESERVATION', 'SETTLEMENT', 'RELEASE', 'REFUND'
 */
public enum TransactionType {
    /** Funds credited to wallet from a confirmed Razorpay payment. */
    TOPUP,
    /** Funds ring-fenced when a session is created (reserved_paise increases). */
    RESERVATION,
    /** Session charge finalised after completion (balance down, reserved down). */
    SETTLEMENT,
    /** Reservation returned — session cancelled before completion. */
    RELEASE,
    /** Manual credit adjustment by platform admin. */
    REFUND
}
