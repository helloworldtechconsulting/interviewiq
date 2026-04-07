package com.interviewiq.auth.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * An employer user — a person who belongs to a company on the platform.
 *
 * <p>Multi-tenant login rule: the login query MUST always scope to
 * {@code company_id}. Never query {@code users} by email alone — the
 * same email address can legally appear in two different companies.
 *
 * <p>Auth method: at least one of {@code passwordHash} or {@code googleSubject}
 * must be non-null (enforced by DB CHECK {@code ck_users_auth_method}).
 *
 * <p>Login gate: {@code emailVerified} must be {@code true}. The
 * {@code AuthService} MUST include {@code email_verified = TRUE} in every
 * login query — unverified users are blocked at the query level.
 *
 * <p>DB table: {@code users} (V002)
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK → companies(id). Immutable after creation. */
    @Column(nullable = false, updatable = false)
    private UUID companyId;

    @Column(nullable = false, length = 255)
    private String fullName;

    /**
     * Stored lowercase. Application layer MUST call {@code email.toLowerCase()}
     * before every INSERT / UPDATE and every login query.
     */
    @Column(nullable = false, length = 255)
    private String email;

    /** BCrypt hash. Null for Google OAuth-only accounts. */
    @Column(length = 255)
    private String passwordHash;

    /** Google OAuth {@code sub} claim. Null for password-only accounts. */
    @Column(length = 255)
    private String googleSubject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UserRole role;

    @Column(nullable = false)
    private boolean isActive = true;

    /**
     * Must be {@code true} before the user can log in.
     * Set via the EMAIL_VERIFICATION OTP flow.
     */
    @Column(nullable = false)
    private boolean emailVerified = false;

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

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getGoogleSubject() { return googleSubject; }
    public void setGoogleSubject(String googleSubject) { this.googleSubject = googleSubject; }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "User{id=" + id + ", companyId=" + companyId + ", email='" + email + "'}";
    }
}
