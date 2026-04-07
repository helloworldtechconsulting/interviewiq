package com.interviewiq.session.domain;

import com.interviewiq.shared.domain.PipelineStatus;
import jakarta.persistence.*;
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

    /** BCrypt hash of the invite token. Raw token is never stored. */
    @Column(nullable = false, unique = true, length = 255)
    private String inviteTokenHash;

    @Column(nullable = false)
    private OffsetDateTime inviteExpiresAt;

    /** Recall.ai bot identifier, assigned asynchronously after bot.joined webhook. */
    @Column(length = 255)
    private String recallBotId;

    /** Google Meet URL supplied to the Recall.ai bot to join the call. */
    @Column(length = 512)
    private String googleMeetUrl;

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
    @Column(columnDefinition = "jsonb")
    private String questionsJson;

    private OffsetDateTime startedAt;

    private OffsetDateTime endedAt;

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

    public String getRecallBotId() { return recallBotId; }
    public void setRecallBotId(String recallBotId) { this.recallBotId = recallBotId; }

    public String getGoogleMeetUrl() { return googleMeetUrl; }
    public void setGoogleMeetUrl(String googleMeetUrl) { this.googleMeetUrl = googleMeetUrl; }

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
