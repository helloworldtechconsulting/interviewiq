package com.interviewiq.billing.dto;

import com.interviewiq.billing.domain.TransactionStatus;
import com.interviewiq.billing.domain.TransactionType;
import com.interviewiq.billing.domain.WalletTransaction;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionResponse(
        UUID              id,
        UUID              companyId,
        UUID              walletId,
        UUID              sessionId,
        TransactionType   transactionType,
        long              amountPaise,
        long              balanceAfterPaise,
        TransactionStatus status,
        String            razorpayOrderId,
        OffsetDateTime    createdAt
) {
    public static TransactionResponse from(WalletTransaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getCompanyId(),
                tx.getWalletId(),
                tx.getSessionId(),
                tx.getTransactionType(),
                tx.getAmountPaise(),
                tx.getBalanceAfterPaise(),
                tx.getStatus(),
                tx.getRazorpayOrderId(),
                tx.getCreatedAt()
        );
    }
}
