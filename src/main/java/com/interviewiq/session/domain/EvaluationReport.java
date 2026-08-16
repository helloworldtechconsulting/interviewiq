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
}
