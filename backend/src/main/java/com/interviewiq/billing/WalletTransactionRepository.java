package com.interviewiq.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {
    List<WalletTransaction> findByCompanyId(UUID companyId);
    List<WalletTransaction> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);
    List<WalletTransaction> findBySessionId(UUID sessionId);
    Page<WalletTransaction> findByCompanyId(UUID companyId, Pageable pageable);

    @Query("SELECT COALESCE(MAX(w.balanceAfterPaise), 0) FROM WalletTransaction w WHERE w.companyId = ?1")
    Long findLatestBalanceByCompanyId(UUID companyId);
}
