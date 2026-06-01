package com.interviewiq.email.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Record of an outbound email sent by the platform.
 *
 * <p>Both {@code companyId} and {@code userId} are nullable and use ON DELETE SET
 * NULL — email delivery history must survive deletion of the recipient or company.
 *
 * <p>No {@code updatedAt} column — the only mutable fields are {@code status},
 * {@code providerMessageId}, and {@code sentAt}, updated by the delivery webhook handler.
 *
 * <p>DB table: {@code email_events} (V015)
 */
@Entity
@Table(name = "email_events")
public class EmailEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Nullable: SET NULL on company deletion. */
    private UUID companyId;

    /** Nullable: SET NULL on user deletion. */
    private UUID userId;

    /** Stored lowercase. Must be non-empty. */
    @Column(nullable = false, length = 255)
    private String recipientEmail;

    @Column(nullable = false, length = 100)
    private String emailType;

    /**
     * Provider-assigned message ID (e.g. SES MessageId, SendGrid X-Message-Id).
     * Used to correlate delivery webhooks back to this record. UNIQUE.
     */
    @Column(length = 255, unique = true)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EmailStatus status = EmailStatus.QUEUED;

    /** Template variables and email body snapshot as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payloadJson;

    /** Set when the email is accepted by the provider (SENT or BOUNCED). */
    private OffsetDateTime sentAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }

    public String getEmailType() { return emailType; }
    public void setEmailType(String emailType) { this.emailType = emailType; }

    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }

    public EmailStatus getStatus() { return status; }
    public void setStatus(EmailStatus status) { this.status = status; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public OffsetDateTime getSentAt() { return sentAt; }
    public void setSentAt(OffsetDateTime sentAt) { this.sentAt = sentAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; } // ← ADD KARO

    @Override
    public String toString() {
        return "EmailEvent{id=" + id + ", recipientEmail='" + recipientEmail + "', status=" + status + "}";
    }
}
