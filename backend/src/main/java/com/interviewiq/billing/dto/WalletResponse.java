package com.interviewiq.billing.dto;

public record WalletResponse(
        Long balancePaise,
        Double balanceRupees
) {
}
