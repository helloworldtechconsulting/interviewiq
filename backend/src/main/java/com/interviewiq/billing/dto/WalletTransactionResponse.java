package com.interviewiq.billing.dto;

import com.interviewiq.billing.TransactionType;

import java.time.LocalDateTime;
import java.util.UUID;

public record WalletTransactionResponse(
        UUID id,
        TransactionType type,
        Long amountPaise,
        String description,
        Long balanceAfterPaise,
        LocalDateTime createdAt
) {
}
