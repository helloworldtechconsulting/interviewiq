package com.interviewiq.session.domain;

import com.interviewiq.ai.domain.HiringRecommendation;
import com.interviewiq.shared.domain.PipelineStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * AI-generated evaluation for a completed interview session.
 *
 * <p>Scalar score columns (overall, technical, communication, relevance,
 * problem_solving) are separate typed columns for DB aggregation (AVG, ORDER BY)
 * — they are NOT embedded in evaluationJson.
 *
 * <p>One report per session — enforced by the UNIQUE constraint on session_id.
 *
 * <p>DB table: {@code evaluation_reports} (V009)
 */
@Entity
@Table(name = "evaluation_reports")
public class EvaluationReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK → companies(id). Immutable after creation. */
    @Column(nullable = false, updatable = false)
    private UUID companyId;

    /**
     * FK → interview_sessions(id) via composite FK (company_id, session_id).
     * UNIQUE — one evaluation per session.
     */
    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    /** 0–100 composite score. Null until generation_status = DONE. */
    private Short overallScore;

    /** 0–10 per-dimension score. */
    private Short technicalScore;

    /** 0–10 per-dimension score. */
    private Short communicationScore;

    /** 0–10 answer relevance to the question. */
    private Short relevanceScore;

    /** 0–10 per-dimension score. */
    private Short problemSolvingScore;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private HiringRecommendation recommendation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PipelineStatus generationStatus = PipelineStatus.PENDING;

    /** Incremented on each pipeline attempt; capped at configured max (e.g. 3). */
    @Column(nullable = false)
    private int generationAttempts = 0;

    /** S3 key for the full verbatim transcript. Too large to embed in DB. */
    @Column(name = "transcript_s3_key", length = 512)
    private String transcriptS3Key;

    /**
     * Structured per-question AI evaluation as a JSON document.
     * Narrative output — not aggregated, not queryable by field.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String evaluationJson;

    /**
     * Per-question narrative evidence (PRD v2.1 §7.6).
     *
     * <p>The PRD is unusually firm here: "every claim cites a specific answer —
     * never a bare score", and "a report whose narrative does not cite answers is
     * a defect, not a stylistic preference". The reasoning is commercial rather
     * than cosmetic — a recruiter who can see <em>why</em> the score is 72 will
     * trust and act on it, where a bare "72" gets ignored, and the quoted
     * evidence is the best defence if a candidate ever challenges a decision.
     *
     * <p>Shape: an overall summary, a 2–3 sentence narrative per dimension, and a
     * narrative per question, each carrying the answer ids it cites. Validated
     * before persisting — a CHECK constraint cannot answer "does this citation
     * point at an answer that exists", so the application does.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_jsonb", columnDefinition = "jsonb")
    private String evidenceJson;

    /** The 3-sentence overall summary shown at the top of the report. */
    @Column(columnDefinition = "TEXT")
    private String summaryText;

    /** Full result JSON in object storage; the scores here are the queryable subset. */
    @Column(name = "report_s3_key", length = 512)
    private String reportS3Key;

    /** Private internal notes, visible only within the employer's company (§7.6). */
    @Column(columnDefinition = "TEXT")
    private String employerNotes;

    /**
     * When an employer first opened this report, or null if nobody has.
     *
     * <p>Set once and never updated. "Last viewed" would answer a different
     * question and would let a re-read make an already-actioned report look
     * fresh again — the counter this feeds is a backlog, not an activity log.
     */
    @Column(name = "viewed_at")
    private OffsetDateTime viewedAt;

    /**
     * When the report became available. The report-ready SLA is measured from
     * session end to this timestamp — 30 minutes hard, ~5 minutes soft, median
     * under 2 (§8, §16).
     */
    private OffsetDateTime generatedAt;

    /**
     * Set when the interview ended with more than 50% but fewer than all
     * questions answered. A partial evaluation is still generated, and is
     * clearly marked <em>Incomplete</em> on the report (§7.5.7).
     */
    @Column(name = "is_partial", nullable = false)
    private boolean partial = false;

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

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public Short getOverallScore() { return overallScore; }
    public void setOverallScore(Short overallScore) { this.overallScore = overallScore; }

    public Short getTechnicalScore() { return technicalScore; }
    public void setTechnicalScore(Short technicalScore) { this.technicalScore = technicalScore; }

    public Short getCommunicationScore() { return communicationScore; }
    public void setCommunicationScore(Short communicationScore) { this.communicationScore = communicationScore; }

    public Short getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(Short relevanceScore) { this.relevanceScore = relevanceScore; }

    public Short getProblemSolvingScore() { return problemSolvingScore; }
    public void setProblemSolvingScore(Short problemSolvingScore) { this.problemSolvingScore = problemSolvingScore; }

    public HiringRecommendation getRecommendation() { return recommendation; }
    public void setRecommendation(HiringRecommendation recommendation) { this.recommendation = recommendation; }

    public PipelineStatus getGenerationStatus() { return generationStatus; }
    public void setGenerationStatus(PipelineStatus generationStatus) { this.generationStatus = generationStatus; }

    public int getGenerationAttempts() { return generationAttempts; }
    public void setGenerationAttempts(int generationAttempts) { this.generationAttempts = generationAttempts; }

    public String getTranscriptS3Key() { return transcriptS3Key; }
    public void setTranscriptS3Key(String transcriptS3Key) { this.transcriptS3Key = transcriptS3Key; }

    public String getEvaluationJson() { return evaluationJson; }
    public void setEvaluationJson(String evaluationJson) { this.evaluationJson = evaluationJson; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "EvaluationReport{id=" + id + ", sessionId=" + sessionId + ", generationStatus=" + generationStatus + "}";
    }

    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }

    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }

    public String getReportS3Key() { return reportS3Key; }
    public void setReportS3Key(String reportS3Key) { this.reportS3Key = reportS3Key; }

    public OffsetDateTime getViewedAt() { return viewedAt; }
    public void setViewedAt(OffsetDateTime viewedAt) { this.viewedAt = viewedAt; }

    public String getEmployerNotes() { return employerNotes; }
    public void setEmployerNotes(String employerNotes) { this.employerNotes = employerNotes; }

    public OffsetDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(OffsetDateTime generatedAt) { this.generatedAt = generatedAt; }

    public boolean isPartial() { return partial; }
    public void setPartial(boolean partial) { this.partial = partial; }
}
