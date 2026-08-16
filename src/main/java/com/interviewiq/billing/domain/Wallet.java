package com.interviewiq.billing.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Company credit wallet for pay-per-session billing.
 *
 * <p>{@code balancePaise} is the total credited amount. {@code reservedPaise}
 * is the ring-fenced portion for in-progress sessions. Available funds are
 * {@code balancePaise - reservedPaise}.
 *
 * <p>{@code version} is used for JPA optimistic locking ({@link Version}) to
 * prevent lost updates under concurrent billing operations.
 *
 * <p>DB table: {@code wallets} (V010)
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK → companies(id). One wallet per company — enforced by UNIQUE constraint. */
    @Column(nullable = false, unique = true, updatable = false)
    private UUID companyId;

    /** Total credited balance in paise (1 INR = 100 paise). Always >= reservedPaise. */
    @Column(nullable = false)
    private long balancePaise = 0L;

    /** Ring-fenced amount for in-progress sessions. Always <= balancePaise. */
    @Column(nullable = false)
    private long reservedPaise = 0L;

    /**
     * Optimistic locking version. Incremented by JPA on every UPDATE.
     * Prevents lost updates under concurrent billing operations.
     */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public long getBalancePaise() { return balancePaise; }
    public void setBalancePaise(long balancePaise) { this.balancePaise = balancePaise; }

    public long getReservedPaise() { return reservedPaise; }
    public void setReservedPaise(long reservedPaise) { this.reservedPaise = reservedPaise; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Wallet{id=" + id + ", companyId=" + companyId + ", balancePaise=" + balancePaise + "}";
    }
}
