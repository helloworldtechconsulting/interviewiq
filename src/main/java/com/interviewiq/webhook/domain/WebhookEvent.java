package com.interviewiq.webhook.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Inbound webhook event from an external provider (Razorpay, System).
 *
 * <p>{@code companyId} is nullable: some events arrive before company context is
 * resolved (e.g. Razorpay events matched asynchronously).
 *
 * <p>The UNIQUE constraint on ({@code provider}, {@code idempotencyKey}) deduplicates
 * provider redeliveries.
 *
 * <p>DB table: {@code webhook_events} (V012)
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Nullable: resolved asynchronously for some provider events. */
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WebhookProvider provider;

    @Column(nullable = false, length = 100)
    private String eventType;

    /** Full raw event payload. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    /**
     * Deduplication key. Format: {PROVIDER}:{provider_event_id}.
     * UNIQUE per (provider, idempotency_key).
     */
    @Column(nullable = false, length = 255)
    private String idempotencyKey;

    @Column(nullable = false)
    private boolean processed = false;

    /** Set when processed transitions to true. Must be non-null if processed = true. */
    private OffsetDateTime processedAt;

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

    public WebhookProvider getProvider() { return provider; }
    public void setProvider(WebhookProvider provider) { this.provider = provider; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }

    public OffsetDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(OffsetDateTime processedAt) { this.processedAt = processedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "WebhookEvent{id=" + id + ", provider=" + provider + ", eventType='" + eventType + "'}";
    }
}
