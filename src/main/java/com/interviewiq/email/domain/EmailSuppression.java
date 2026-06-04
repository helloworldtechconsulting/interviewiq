package com.interviewiq.email.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Email suppression list for AWS SES bounce and complaint management.
 *
 * <p>Every outbound email dispatch MUST check this table before sending.
 * If the recipient email is present, the send is silently skipped and an
 * {@link EmailEvent} row is written with status {@code SUPPRESSED}.
 *
 * <p>Continued sending to bounced or complaining addresses degrades sender
 * reputation and violates the AWS Acceptable Use Policy for SES.
 *
 * <p>One row per email address (UNIQUE on email). Duplicate webhook events from
 * SES are handled by an application-layer EXISTS check before INSERT.
 *
 * <p>GDPR note: rows are retained indefinitely. A "right to erasure" request
 * should replace the email value with a one-way hash rather than deleting the row.
 *
 * <p>DB table: {@code email_suppressions} (V036)
 */
@Entity
@Table(name = "email_suppressions")
public class EmailSuppression {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Lowercase email address.
     * DB enforces: UNIQUE, lowercase CHECK, non-empty CHECK.
     */
    @Column(nullable = false, length = 255, unique = true)
    private String email;

    /**
     * Why this address is suppressed.
     * DB CHECK enforces: BOUNCE, COMPLAINT, MANUAL.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SuppressionReason reason;

    /**
     * AWS SES / SNS notification ID for traceability back to the originating event.
     * Stored for audit purposes only; not used for deduplication (email handles that).
     */
    @Column(name = "provider_notification_id", length = 255)
    private String providerNotificationId;

    /**
     * Optional operator note when reason = MANUAL.
     * E.g. "user requested opt-out via support ticket #1234".
     */
    @Column(columnDefinition = "TEXT")
    private String notes;

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

    public SuppressionReason getReason() { return reason; }
    public void setReason(SuppressionReason reason) { this.reason = reason; }

    public String getProviderNotificationId() { return providerNotificationId; }
    public void setProviderNotificationId(String providerNotificationId) { this.providerNotificationId = providerNotificationId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "EmailSuppression{id=" + id + ", email='" + email + "', reason=" + reason + "}";
    }
}
