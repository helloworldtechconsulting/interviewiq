package com.interviewiq.billing.infrastructure;

import com.interviewiq.billing.domain.TransactionStatus;
import com.interviewiq.billing.domain.TransactionType;
import com.interviewiq.billing.domain.WalletTransaction;
import com.interviewiq.session.domain.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    Page<WalletTransaction> findAllByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    /** Locate the pending reservation for a session before settlement or release. */
    Optional<WalletTransaction> findByWalletIdAndSessionIdAndTransactionTypeAndStatus(
            UUID walletId, UUID sessionId, TransactionType type, TransactionStatus status);

    Optional<WalletTransaction> findByRazorpayOrderId(String razorpayOrderId);

    /** All pending reservations for a wallet — used by WalletService reconciliation. */
    List<WalletTransaction> findAllByWalletIdAndTransactionTypeAndStatus(
            UUID walletId, TransactionType type, TransactionStatus status);

    /**
     * A page of PENDING RESERVATION transactions whose session has reached a given
     * status (EXPIRED) — used by {@code StrandedReservationCleanupRunner} to find
     * reservations stranded by the pre-fix expiry job.
     *
     * <p>The session filter is applied in the query (via a subquery over
     * {@link com.interviewiq.session.domain.InterviewSession}) so that every returned
     * row is genuinely releasable. That lets the caller iterate by always re-fetching
     * the first page: each released reservation leaves the PENDING set and the result
     * shrinks to empty, with no non-releasable rows left clogging the page.
     */
    @Query("SELECT t FROM WalletTransaction t "
            + "WHERE t.transactionType = :type AND t.status = :status "
            + "AND t.sessionId IN (SELECT s.id FROM InterviewSession s WHERE s.status = :sessionStatus)")
    Page<WalletTransaction> findReservationsForSessionsInStatus(
            @Param("type") TransactionType type,
            @Param("status") TransactionStatus status,
            @Param("sessionStatus") SessionStatus sessionStatus,
            Pageable pageable);
}
