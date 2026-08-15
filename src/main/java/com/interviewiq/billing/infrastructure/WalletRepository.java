package com.interviewiq.billing.infrastructure;

import com.interviewiq.billing.domain.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /** One wallet per company — UNIQUE constraint enforced at DB level. */
    Optional<Wallet> findByCompanyId(UUID companyId);

    /**
     * Loads a wallet under a row lock, for every path that moves money.
     *
     * <p>PRD v2.1 §7.8.1: "Settlement must be idempotent per session. With
     * multiple pods running, the settlement write must be guarded by the same
     * row-lock discipline as evaluation or a session can be double-charged." The
     * same applies to reservation and to promotional spend ordering — §7.9 groups
     * "two settlements racing on the same promotional balance" with duplicate
     * evaluation as one class of bug.
     *
     * <p>Reading the balance, deciding which pot to spend from and writing the
     * result is a read-modify-write. Unlocked, two pods interleave and one
     * decision is lost.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.companyId = :companyId")
    Optional<Wallet> findByCompanyIdForUpdate(@Param("companyId") UUID companyId);

    boolean existsByCompanyId(UUID companyId);

    /**
     * Total outstanding promotional credit across all companies.
     *
     * <p>Feeds the promotional-exposure metric — grants are capped and monitored
     * (§7.8.3), and the internal dashboard tracks the total.
     */
    @Query("SELECT COALESCE(SUM(w.promoBalancePaise), 0) FROM Wallet w")
    long totalPromotionalExposurePaise();
}
