package com.interviewiq.billing.dto;

import com.interviewiq.billing.domain.Wallet;

import java.util.UUID;

public record WalletResponse(
        UUID   id,
        UUID   companyId,
        long   balancePaise,
        long   reservedPaise,
        long   availablePaise
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getCompanyId(),
                wallet.getBalancePaise(),
                wallet.getReservedPaise(),
                wallet.getBalancePaise() - wallet.getReservedPaise()
        );
    }
}
