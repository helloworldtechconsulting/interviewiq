package com.interviewengine.billing.domain;

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

    /**
     * PAID balance in paise (1 INR = 100 paise) — money the company actually
     * bought through Razorpay. This is the balance GST invoices are generated
     * against.
     */
    @Column(nullable = false)
    private long balancePaise = 0L;

    /**
     * PROMOTIONAL balance in paise — free credit granted by staff or at signup
     * (PRD v2.1 §7.8.3).
     *
     * <p>Kept separate from the paid balance rather than merged into it, because
     * promotional credit is not a sale: it is excluded from GST invoices and from
     * revenue reporting, and the dashboard must show the split so a customer is
     * never surprised about which money is being spent.
     *
     * <p><strong>Promotional credit is always spent first.</strong> Never the
     * reverse — a customer seeing paid money consumed while free credit sits
     * unused is a refund request and a trust problem.
     */
    @Column(nullable = false)
    private long promoBalancePaise = 0L;

    /**
     * Ring-fenced amount for in-progress sessions and pending imports. A
     * reservation is a claim on the <em>combined</em> balance; which pot settles
     * it is decided at settlement time by the promotional-first spend ordering.
     */
    @Column(nullable = false)
    private long reservedPaise = 0L;

    /**
     * Optimistic locking version. Incremented by JPA on every UPDATE.
     * Prevents lost updates under concurrent billing operations.
     */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    /**
     * When the low-balance email was last sent, or null if the balance has not
     * been low since the last top-up.
     *
     * <p>Cleared on top-up rather than on crossing back above the threshold, so
     * a company that tops up and later drops low again is warned again — while a
     * company that simply sits below the line is not emailed on every settle.
     * Without this the alert would fire once per completed interview, which is
     * how a useful warning becomes a filtered one.
     */
    @Column(name = "low_balance_notified_at")
    private OffsetDateTime lowBalanceNotifiedAt;

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

    public long getPromoBalancePaise() { return promoBalancePaise; }
    public void setPromoBalancePaise(long promoBalancePaise) { this.promoBalancePaise = promoBalancePaise; }

    /** Sets both balances at once. Convenience for setup and tests. */
    public void setPaidAndPromo(long paidPaise, long promoPaise) {
        this.balancePaise = paidPaise;
        this.promoBalancePaise = promoPaise;
    }

    /** Paid plus promotional. What a reservation is checked against. */
    public long getTotalBalancePaise() { return balancePaise + promoBalancePaise; }

    /** Combined balance not already ring-fenced by an existing reservation. */
    public long getAvailablePaise() { return getTotalBalancePaise() - reservedPaise; }

    /**
     * Whether the low-balance banner and alert email should fire (PRD §7.7,
     * §7.8.2). The threshold counts paid and promotional together.
     */
    public boolean isLowBalance(long thresholdPaise) {
        return getTotalBalancePaise() <= thresholdPaise;
    }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public OffsetDateTime getLowBalanceNotifiedAt() { return lowBalanceNotifiedAt; }
    public void setLowBalanceNotifiedAt(OffsetDateTime v) { this.lowBalanceNotifiedAt = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Wallet{id=" + id + ", companyId=" + companyId + ", balancePaise=" + balancePaise + "}";
    }
}
