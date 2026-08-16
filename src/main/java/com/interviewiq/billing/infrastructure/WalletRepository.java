package com.interviewiq.billing.infrastructure;

import com.interviewiq.billing.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /** One wallet per company — UNIQUE constraint enforced at DB level. */
    Optional<Wallet> findByCompanyId(UUID companyId);

    boolean existsByCompanyId(UUID companyId);
}
