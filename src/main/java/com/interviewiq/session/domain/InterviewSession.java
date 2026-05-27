package com.interviewiq.session.domain;

import com.interviewiq.shared.domain.PipelineStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A scheduled interview session linking a candidate to a job opening.
 *
 * <p>Status is the authoritative lifecycle state: INVITED → STARTED →
 * COMPLETED | EXPIRED | ERROR | CANCELLED.
 *
 * <p>The {@code questionsJson} JSONB field is written once after the AI
 * question generation pipeline completes. Check {@code questionGenerationStatus}
 * before reading it.
 *
 * <p>DB table: {@code interview_sessions} (V007)
 */
@Entity
@Table(name = "interview_sessions")
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK → companies(id). Immutable after creation. */
    @Column(nullable = false, updatable = false)
    private UUID companyId;

    /**
     * FK → job_openings(id) via composite FK (company_id, job_opening_id).
     * DB enforces this job opening belongs to the same company.
     */
    @Column(nullable = false, updatable = false)
    private UUID jobOpeningId;

    /**
     * FK → candidates(id) via composite FK (company_id, candidate_id).
     * DB enforces this candidate belongs to the same company.
     */
    @Column(nullable = false, updatable = false)
    private UUID candidateId;

    /**
     * Target interview time set by the recruiter at session creation.
     * Shown to the candidate: "Your interview is scheduled for April 20 at 3:00 PM IST".
     * NULL for sessions created before V033. Added V033.
     */
    private OffsetDateTime scheduledAt;

    /** BCrypt hash of the invite token. Raw token is never stored. */
    @Column(nullable = false, unique = true, length = 255)
    private String inviteTokenHash;

    @Column(nullable = false)
    private OffsetDateTime inviteExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SessionStatus status = SessionStatus.INVITED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PipelineStatus questionGenerationStatus = PipelineStatus.PENDING;

    /**
     * Pre-generated question set as a JSON array. Written once after LLM pipeline
     * completes. Structure: [{"order":1,"text":"...","dimension":"TECHNICAL"}, ...]
     * NULL while questionGenerationStatus is PENDING, IN_PROGRESS, or FAILED.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String questionsJson;

    private OffsetDateTime startedAt;

    private OffsetDateTime endedAt;

    /**
     * SHA-256 hash of the ephemeral WebSocket room token.
     * Issued when the session transitions to STARTED; raw token never persisted. Added V032.
     */
    @Column(unique = true, length = 255)
    private String roomTokenHash;

    /** Expiry for the room token. Set/null alongside roomTokenHash. Added V032. */
    private OffsetDateTime roomTokenExpiresAt;

    /**
     * Actual interview duration in seconds, computed from (endedAt − startedAt) at session-end.
     * Stored explicitly to avoid recomputing on every query. Added V032.
     */
    private Integer durationSeconds;

    /**
     * S3 object key for the session video recording (WebM).
     * Uploaded by the browser via pre-signed PUT URL at session end.
     * S3 lifecycle auto-deletes after 7 days. Added V032.
     */
    @Column(name = "recording_s3_key", length = 512)
    private String recordingS3Key;

    /**
     * Denormalised anti-cheat summary written by the evaluation pipeline after
     * the session ends. JSON array of flag objects. Added V032.
     * Structure: [{"type":"TAB_SWITCH","count":2,"firstOccurrence":"..."}]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proctoring_flags_jsonb", columnDefinition = "jsonb")
    private String proctoringFlagsJsonb;

    /**
     * Timestamp set when the session transitions to CANCELLED.
     * Required for cancellation analytics and refund eligibility windows. Added V032.
     */
    private OffsetDateTime cancelledAt;

    /** Structured error code for ERROR state. E.g. BOT_JOIN_TIMEOUT. */
    @Column(length = 100)
    private String errorCode;

    /** Human-readable error detail for incident debugging. Not shown in employer UI. */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

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

    public UUID getJobOpeningId() { return jobOpeningId; }
    public void setJobOpeningId(UUID jobOpeningId) { this.jobOpeningId = jobOpeningId; }

    public UUID getCandidateId() { return candidateId; }
    public void setCandidateId(UUID candidateId) { this.candidateId = candidateId; }

    public String getInviteTokenHash() { return inviteTokenHash; }
    public void setInviteTokenHash(String inviteTokenHash) { this.inviteTokenHash = inviteTokenHash; }

    public OffsetDateTime getInviteExpiresAt() { return inviteExpiresAt; }
    public void setInviteExpiresAt(OffsetDateTime inviteExpiresAt) { this.inviteExpiresAt = inviteExpiresAt; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public PipelineStatus getQuestionGenerationStatus() { return questionGenerationStatus; }
    public void setQuestionGenerationStatus(PipelineStatus questionGenerationStatus) { this.questionGenerationStatus = questionGenerationStatus; }

    public String getQuestionsJson() { return questionsJson; }
    public void setQuestionsJson(String questionsJson) { this.questionsJson = questionsJson; }

    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(OffsetDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(OffsetDateTime endedAt) { this.endedAt = endedAt; }

    public String getRoomTokenHash() { return roomTokenHash; }
    public void setRoomTokenHash(String roomTokenHash) { this.roomTokenHash = roomTokenHash; }

    public OffsetDateTime getRoomTokenExpiresAt() { return roomTokenExpiresAt; }
    public void setRoomTokenExpiresAt(OffsetDateTime roomTokenExpiresAt) { this.roomTokenExpiresAt = roomTokenExpiresAt; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getRecordingS3Key() { return recordingS3Key; }
    public void setRecordingS3Key(String recordingS3Key) { this.recordingS3Key = recordingS3Key; }

    public String getProctoringFlagsJsonb() { return proctoringFlagsJsonb; }
    public void setProctoringFlagsJsonb(String proctoringFlagsJsonb) { this.proctoringFlagsJsonb = proctoringFlagsJsonb; }

    public OffsetDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(OffsetDateTime cancelledAt) { this.cancelledAt = cancelledAt; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "InterviewSession{id=" + id + ", candidateId=" + candidateId + ", status=" + status + "}";
    }
}
