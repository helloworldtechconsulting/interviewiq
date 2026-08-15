package com.interviewiq.billing.infrastructure;

import com.interviewiq.billing.domain.TransactionStatus;
import com.interviewiq.billing.domain.TransactionType;
import com.interviewiq.billing.domain.WalletTransaction;
import jakarta.persistence.LockModeType;
import com.interviewiq.session.domain.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    Page<WalletTransaction> findAllByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    /** Locate the pending reservation for a session before settlement or release. */
    Optional<WalletTransaction> findByWalletIdAndSessionIdAndTransactionTypeAndStatus(
            UUID walletId, UUID sessionId, TransactionType type, TransactionStatus status);

    Optional<WalletTransaction> findByRazorpayOrderId(String razorpayOrderId);

    /** All pending reservations for a wallet — used by WalletService reconciliation. */
    List<WalletTransaction> findAllByWalletIdAndTransactionTypeAndStatus(
            UUID walletId, TransactionType type, TransactionStatus status);

    @Query("SELECT t FROM WalletTransaction t "
            + "WHERE t.transactionType = :type AND t.status = :status "
            + "AND t.sessionId IN (SELECT s.id FROM InterviewSession s WHERE s.status = :sessionStatus)")
    Page<WalletTransaction> findReservationsForSessionsInStatus(
            @Param("type") TransactionType type,
            @Param("status") TransactionStatus status,
            @Param("sessionStatus") SessionStatus sessionStatus,
            Pageable pageable);

    /** Idempotency guard on settlement — a session may be charged exactly once (§7.8.1). */
    boolean existsByWalletIdAndSessionIdAndTransactionType(
            UUID walletId, UUID sessionId, TransactionType transactionType);

    /**
     * Claims promotional grants that have lapsed, for the expiry sweep.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} per §7.9 — PromoCreditExpiryJob is named
     * in the list of workers that must claim rather than poll, and reversing the
     * same grant twice would take a company's balance below what it was granted.
     *
     * <p>A grant is claimable once its expiry has passed and no reversing
     * PROMO_EXPIRY entry references it yet.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = """
           SELECT * FROM wallet_transactions t
           WHERE t.transaction_type = 'PROMO_CREDIT'
             AND t.expires_at IS NOT NULL
             AND t.expires_at < :now
             AND t.status = 'CONFIRMED'
           ORDER BY t.expires_at ASC
           LIMIT :batchSize
           FOR UPDATE SKIP LOCKED
           """, nativeQuery = true)
    List<WalletTransaction> claimExpiredPromotionalGrants(@Param("now") OffsetDateTime now,
                                                          @Param("batchSize") int batchSize);

    /** Marks a swept grant so it is not reversed twice. */
    @Modifying
    @Query(value = "UPDATE wallet_transactions SET status = 'RELEASED' WHERE id = :id",
           nativeQuery = true)
    int markGrantExpired(@Param("id") UUID id);

    /**
     * Paid top-ups only, for GST invoicing.
     *
     * <p>"Invoices show paid top-ups only. If free credit appears on an invoice,
     * the accounting and the tax filing disagree with each other." (§7.8.3)
     */
    @Query("""
           SELECT t FROM WalletTransaction t
           WHERE t.companyId = :companyId
             AND t.transactionType = com.interviewiq.billing.domain.TransactionType.TOPUP
           ORDER BY t.createdAt DESC
           """)
    List<WalletTransaction> findInvoiceableTopUps(@Param("companyId") UUID companyId);

    /**
     * Earliest expiry among a company's outstanding promotional grants, or null
     * if none of them expire. Shown alongside the balance so the customer knows
     * when free credit lapses.
     */
    @Query("""
           SELECT MIN(t.expiresAt) FROM WalletTransaction t
           WHERE t.companyId = :companyId
             AND t.transactionType = com.interviewiq.billing.domain.TransactionType.PROMO_CREDIT
             AND t.expiresAt IS NOT NULL
             AND t.status = com.interviewiq.billing.domain.TransactionStatus.CONFIRMED
           """)
    OffsetDateTime earliestOutstandingGrantExpiry(@Param("companyId") UUID companyId);
}
