package com.interviewiq.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Which capacity buckets a given session occupies.
 *
 * <p>Held explicitly rather than recomputed from {@code scheduledStartAt} plus
 * the duration tier, because release has to be exact. When a session is
 * cancelled, expires or is rescheduled, precisely the buckets that were taken
 * must be freed — even if the job's tier has been changed in the meantime, or
 * the platform bucket width were ever revised. Recomputation would silently leak
 * or double-free capacity in exactly those cases.
 */
@Entity
@Table(name = "session_capacity_reservations")
public class SessionCapacityReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID companyId;

    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false)
    private UUID capacityBucketId;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public static SessionCapacityReservation of(UUID companyId, UUID sessionId, UUID bucketId) {
        SessionCapacityReservation r = new SessionCapacityReservation();
        r.companyId = companyId;
        r.sessionId = sessionId;
        r.capacityBucketId = bucketId;
        return r;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getCapacityBucketId() { return capacityBucketId; }
    public void setCapacityBucketId(UUID capacityBucketId) { this.capacityBucketId = capacityBucketId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
