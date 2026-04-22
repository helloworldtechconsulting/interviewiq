package com.interviewiq.session.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Append-only anti-cheat and session lifecycle event log.
 *
 * <p>The candidate's browser emits real-time events (tab switches, camera off,
 * multi-face detection, etc.) during an interview. These are received by the
 * backend and stored here as an immutable audit trail.
 *
 * <p>This is DISTINCT from {@link SessionNote} (recruiter annotations).
 * {@code SessionEvent} is raw machine-generated signal at high frequency.
 *
 * <p>The employer UI reads {@code proctoring_flags_jsonb} on
 * {@link InterviewSession} for the summary card; this table is read only
 * by the evaluation pipeline rollup and compliance exports.
 *
 * <p>Write pattern: append-only. No UPDATE or DELETE in production.
 *
 * <p>DB table: {@code session_events} (V035)
 */
@Entity
@Table(name = "session_events")
public class SessionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK → companies(id). Denormalised for composite FK enforcement. */
    @Column(nullable = false, updatable = false)
    private UUID companyId;

    /**
     * FK → interview_sessions(id) via composite FK (company_id, session_id).
     * ON DELETE CASCADE: events are removed if the session is hard-deleted.
     */
    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    /**
     * Browser-reported event type. Drives proctoring analysis and scoring.
     * DB CHECK enforces valid values.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private SessionEventType eventType;

    /**
     * Event-specific payload: {"faceCount": 2}, {"durationSeconds": 12}, etc.
     * Optional — lifecycle events may carry no payload.
     * DB CHECK: must be a JSON object when present.
     */
    @Column(columnDefinition = "jsonb")
    private String metadata;

    /**
     * Server-received timestamp. Authoritative for ordering and replay protection.
     * If client-side timestamp is needed for drift analysis, embed it in metadata.
     */
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

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public SessionEventType getEventType() { return eventType; }
    public void setEventType(SessionEventType eventType) { this.eventType = eventType; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "SessionEvent{id=" + id + ", sessionId=" + sessionId + ", eventType=" + eventType + "}";
    }
}
