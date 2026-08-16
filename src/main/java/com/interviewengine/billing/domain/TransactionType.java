package com.interviewengine.billing.domain;

/**
 * Wallet transaction types.
 *
 * <p>DB CHECK values: {@code 'TOPUP'}, {@code 'PROMO_CREDIT'}, {@code 'PROMO_EXPIRY'},
 * {@code 'RESERVATION'}, {@code 'SETTLEMENT'}, {@code 'RELEASE'}, {@code 'REFUND'}
 * (see V049).
 */
public enum TransactionType {

    /** Funds credited from a confirmed Razorpay payment. Paid, invoiced, GST-bearing. */
    TOPUP,

    /**
     * Free wallet balance granted by staff or at signup (PRD v2.1 §7.8.3).
     *
     * <p>A distinct type rather than a flag on {@code TOPUP}, for three reasons
     * that all bite at once: promotional credit must stay out of revenue
     * reporting; it is <strong>excluded from GST invoices</strong> because it is
     * not a sale, and if free credit appeared on an invoice the accounting and
     * the tax filing would disagree with each other; and the dashboard must show
     * the paid/promotional split, because a customer must never be surprised
     * about which money is being spent.
     *
     * <p>Granted from the internal staff console only, with a mandatory reason
     * and an optional expiry, and every grant is audited. There is no
     * employer-facing path that can create one.
     */
    PROMO_CREDIT,

    /** Reversing entry written by the sweep when unspent promotional credit lapses. */
    PROMO_EXPIRY,

    /** Funds ring-fenced when a session is created (reserved_paise increases). */
    RESERVATION,

    /** Session charge finalised after completion (balance down, reserved down). */
    SETTLEMENT,

    /** Reservation returned — session cancelled, expired, or an import refused. */
    RELEASE,

    /** Manual credit adjustment by platform staff. */
    REFUND;

    /**
     * Whether this type moves promotional rather than paid balance.
     *
     * <p>Used to keep promotional movement out of invoices and revenue reports.
     */
    public boolean isPromotional() {
        return this == PROMO_CREDIT || this == PROMO_EXPIRY;
    }

    /**
     * Whether this type appears on a GST invoice.
     *
     * <p>Invoices cover paid top-ups only (§7.8.3, §8 Tax).
     */
    public boolean isInvoiceable() {
        return this == TOPUP;
    }
}
