package com.interviewiq.billing;

import com.interviewiq.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "wallet_transactions", indexes = {
        @Index(name = "idx_transactions_company", columnList = "company_id"),
        @Index(name = "idx_transactions_session", columnList = "session_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletTransaction extends BaseEntity {

    @Column(nullable = false)
    private UUID companyId;

    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false)
    private Long amountPaise;

    private String razorpayPaymentId;

    private String description;

    @Column(nullable = false)
    private Long balanceAfterPaise;
}
