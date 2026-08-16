package com.interviewengine.billing.infrastructure;

import com.interviewengine.billing.domain.TransactionStatus;
import com.interviewengine.billing.domain.TransactionType;
import com.interviewengine.billing.domain.WalletTransaction;
import jakarta.persistence.LockModeType;
import com.interviewengine.session.domain.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
             AND t.transactionType = com.interviewengine.billing.domain.TransactionType.TOPUP
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
             AND t.transactionType = com.interviewengine.billing.domain.TransactionType.PROMO_CREDIT
             AND t.expiresAt IS NOT NULL
             AND t.status = com.interviewengine.billing.domain.TransactionStatus.CONFIRMED
           """)
    OffsetDateTime earliestOutstandingGrantExpiry(@Param("companyId") UUID companyId);

    // ── Platform-staff aggregates (INTIQ-35) ─────────────────────────────────

    /**
     * Lifetime spend per company, as {@code [companyId, sumPaise]}.
     *
     * <p>Summed over SETTLEMENT rather than TOPUP deliberately: a top-up is money
     * received but not yet earned, whereas a settlement is an interview actually
     * delivered. "What has this customer spent with us" is the second number.
     */
    @Query("""
           SELECT t.companyId, COALESCE(SUM(t.amountPaise), 0) FROM WalletTransaction t
           WHERE t.companyId IN :companyIds AND t.transactionType = :type
           GROUP BY t.companyId
           """)
    List<Object[]> sumByCompanyIdInAndType(@Param("companyIds") Collection<UUID> companyIds,
                                           @Param("type") TransactionType type);

    @Query("""
           SELECT COALESCE(SUM(t.amountPaise), 0) FROM WalletTransaction t
           WHERE t.transactionType = :type
           """)
    long sumByType(@Param("type") TransactionType type);
}
