package com.interviewiq.billing.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Immutable ledger entry recording a wallet balance movement.
 *
 * <p>No {@code updatedAt}: transactions are append-only; the row is never mutated
 * after creation (the {@code status} column is the only exception, updated from
 * PENDING → CONFIRMED | RELEASED | FAILED).
 *
 * <p>V026 added: {@code status}, {@code razorpayOrderId}, and re-aligned
 * {@code transactionType} enum values to TOPUP / RESERVATION / SETTLEMENT /
 * RELEASE / REFUND.
 *
 * <p>DB table: {@code wallet_transactions} (V011 + V026)
 */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK → companies(id). Immutable after creation. */
    @Column(nullable = false, updatable = false)
    private UUID companyId;

    /**
     * FK → wallets(id) via composite FK (company_id, wallet_id).
     * DB enforces this wallet belongs to the same company.
     */
    @Column(nullable = false, updatable = false)
    private UUID walletId;

    /**
     * FK → interview_sessions(id) via composite FK (company_id, session_id).
     * Nullable: TOPUP and REFUND transactions are not session-linked.
     * DB uses ON DELETE SET NULL (session_id only).
     */
    @Column(updatable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionType transactionType;

    /** Always a positive magnitude. Sign semantics are carried by transactionType. */
    @Column(nullable = false)
    private long amountPaise;

    /** Wallet balance snapshot immediately after this transaction was applied. */
    @Column(nullable = false)
    private long balanceAfterPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionStatus status = TransactionStatus.CONFIRMED;

    /**
     * Razorpay order ID. Populated only on TOPUP transactions.
     * UNIQUE constraint prevents double-crediting if confirmTopUp() fires twice.
     */
    @Column(length = 255, unique = true)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 255, unique = true)
    private String razorpayPaymentId;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public UUID getWalletId() { return walletId; }
    public void setWalletId(UUID walletId) { this.walletId = walletId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }

    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }

    public long getBalanceAfterPaise() { return balanceAfterPaise; }
    public void setBalanceAfterPaise(long balanceAfterPaise) { this.balanceAfterPaise = balanceAfterPaise; }

    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }

    public String getRazorpayOrderId() { return razorpayOrderId; }
    public void setRazorpayOrderId(String razorpayOrderId) { this.razorpayOrderId = razorpayOrderId; }

    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String razorpayPaymentId) { this.razorpayPaymentId = razorpayPaymentId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "WalletTransaction{id=" + id + ", type=" + transactionType + ", amountPaise=" + amountPaise + "}";
    }
}
