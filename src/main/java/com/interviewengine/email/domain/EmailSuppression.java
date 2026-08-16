package com.interviewengine.email.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "email_suppressions")
public class EmailSuppression {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 255, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SuppressionReason reason;

    @Column(name = "provider_notification_id", length = 255)
    private String providerNotificationId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

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
