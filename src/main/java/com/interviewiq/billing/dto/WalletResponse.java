package com.interviewiq.billing.dto;

import com.interviewiq.billing.domain.Wallet;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wallet balance, split into paid and promotional (PRD v2.1 §7.7, §7.8.2).
 *
 * <p>The split is surfaced rather than summed because "a customer must never be
 * surprised about which money is being spent" (§7.8.3). The dashboard renders
 * this as, for example, <em>"Balance ₹700 (₹200 promotional, expires 30 Sep)"</em>.
 *
 * @param paidBalancePaise      money the company bought — the balance GST invoices are drawn against
 * @param promoBalancePaise     free credit, spent first and never invoiced
 * @param totalBalancePaise     paid plus promotional; what the low-balance threshold is measured against
 * @param reservedPaise         ring-fenced against sessions already invited or imports in flight
 * @param availablePaise        total minus reserved — what a new session can draw on
 * @param promoExpiresAt        earliest expiry among outstanding grants, or null if none expire
 * @param lowBalance            whether the persistent low-balance banner should show
 */
public record WalletResponse(
        UUID   id,
        UUID   companyId,
        long   paidBalancePaise,
        long   promoBalancePaise,
        long   totalBalancePaise,
        long   reservedPaise,
        long   availablePaise,
        OffsetDateTime promoExpiresAt,
        boolean lowBalance
) {
    public static WalletResponse from(Wallet wallet, OffsetDateTime promoExpiresAt, long lowBalanceThresholdPaise) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getCompanyId(),
                wallet.getBalancePaise(),
                wallet.getPromoBalancePaise(),
                wallet.getTotalBalancePaise(),
                wallet.getReservedPaise(),
                wallet.getAvailablePaise(),
                promoExpiresAt,
                wallet.isLowBalance(lowBalanceThresholdPaise)
        );
    }
}
