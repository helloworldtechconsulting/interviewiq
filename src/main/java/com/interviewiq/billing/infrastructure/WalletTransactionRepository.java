package com.interviewiq.billing.infrastructure;

import com.interviewiq.billing.domain.TransactionStatus;
import com.interviewiq.billing.domain.TransactionType;
import com.interviewiq.billing.domain.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
