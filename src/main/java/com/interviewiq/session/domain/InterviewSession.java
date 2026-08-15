package com.interviewiq.session.domain;

import com.interviewiq.job.domain.DurationTier;
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
 * <p>Status is the authoritative lifecycle state: INVITED → IN_PROGRESS →
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
     * When the candidate booked their interview for. NULL until they choose a
     * time, and skipped entirely on the "Start now" path (PRD v2.1 §7.4.1).
     */
    private OffsetDateTime scheduledStartAt;

    /**
     * Copied from the job opening at session creation rather than read through
     * the job. If an employer changes a job's tier after invites are out, the
     * hard timer and bucket occupancy of already-booked sessions must not move
     * under them.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DurationTier durationTier = DurationTier.defaultTier();

    /**
     * When question generation finished. This is what the readiness gate tests
     * (§7.4.3) — "Start now" is offered once this is set — and the measurement
     * point for the question-generation SLA (max 30 minutes, typically ~20s).
     */
    private OffsetDateTime questionsReadyAt;

    /**
     * Recorded when the candidate had no résumé, so questions were generated
     * from the JD alone. Surfaced on the report (§7.3).
     */
    @Column(nullable = false)
    private boolean resumeMissing = false;

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

    @Column(unique = true, length = 255)
    private String roomTokenHash;

    private OffsetDateTime roomTokenExpiresAt;

    private Integer durationSeconds;

    /**
     * 480p WebM uploaded browser-to-storage by pre-signed PUT. The recording
     * never traverses our application servers (§7.5.3).
     */
    @Column(name = "recording_s3_key", length = 512)
    private String recordingS3Key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proctoring_flags_jsonb", columnDefinition = "jsonb")
    private String proctoringFlagsJsonb;

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

    public OffsetDateTime getScheduledStartAt() { return scheduledStartAt; }
    public void setScheduledStartAt(OffsetDateTime scheduledStartAt) { this.scheduledStartAt = scheduledStartAt; }

    public DurationTier getDurationTier() { return durationTier; }
    public void setDurationTier(DurationTier durationTier) { this.durationTier = durationTier; }

    public OffsetDateTime getQuestionsReadyAt() { return questionsReadyAt; }
    public void setQuestionsReadyAt(OffsetDateTime questionsReadyAt) { this.questionsReadyAt = questionsReadyAt; }

    public boolean isResumeMissing() { return resumeMissing; }
    public void setResumeMissing(boolean resumeMissing) { this.resumeMissing = resumeMissing; }

    /**
     * Whether the readiness gate (§7.4.3) is satisfied — questions have finished
     * generating, so "Start now" may be offered.
     */
    public boolean areQuestionsReady() { return questionsReadyAt != null; }
}
