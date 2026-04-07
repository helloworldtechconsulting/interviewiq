package com.interviewiq.auth.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A single-use OTP issued for email verification, password reset, or
 * candidate authentication.
 *
 * <p>No FK to {@code users}: OTPs are issued before a user row may exist
 * (EMAIL_VERIFICATION during registration, CANDIDATE_AUTH where no user
 * row exists at all). The record is keyed only by {@code email} and
 * {@code purpose}.
 *
 * <p>Lifecycle: create → validate (check hash + expiry + used=false) → mark used.
 * Expired/used records are pruned by a scheduled cleanup job via the partial
 * index {@code idx_otp_records_cleanup}.
 *
 * <p>DB table: {@code otp_records} (V004)
 */
@Entity
@Table(name = "otp_records")
public class OtpRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Stored lowercase. Must be normalised before every INSERT and lookup.
     */
    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private OtpPurpose purpose;

    /** BCrypt hash of the 6-digit OTP. Raw OTP is never persisted. */
    @Column(nullable = false, length = 255)
    private String otpHash;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public OtpPurpose getPurpose() { return purpose; }
    public void setPurpose(OtpPurpose purpose) { this.purpose = purpose; }

    public String getOtpHash() { return otpHash; }
    public void setOtpHash(String otpHash) { this.otpHash = otpHash; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
