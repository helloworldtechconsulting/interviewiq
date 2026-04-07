package com.interviewiq.candidate.domain;

import com.interviewiq.shared.domain.PipelineStatus;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A candidate invited to interview for a specific job opening.
 *
 * <p>No {@code status} column: the session's status is the authoritative
 * state tracker. The candidate list view derives display status by joining
 * to {@code interview_sessions}.
 *
 * <p>Resume text ({@code resumeText}) is the second AI pipeline input.
 * Without it, questions reflect only the JD — the "personalised from JD +
 * resume" product differentiator is unavailable.
 *
 * <p>DB table: {@code candidates} (V006)
 */
@Entity
@Table(name = "candidates")
public class Candidate {

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

    /** Stored lowercase. Unique per (job_opening_id, email). */
    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 255)
    private String fullName;

    /** S3 object key for the uploaded resume file. */
    @Column(name = "resume_s3_key", length = 512)
    private String resumeS3Key;

    /**
     * Extracted plain-text content of the resume. Null while extraction is in
     * progress. Used as the second input to the AI question generation prompt.
     */
    @Column(columnDefinition = "TEXT")
    private String resumeText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PipelineStatus resumeExtractionStatus = PipelineStatus.PENDING;

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

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getResumeS3Key() { return resumeS3Key; }
    public void setResumeS3Key(String resumeS3Key) { this.resumeS3Key = resumeS3Key; }

    public String getResumeText() { return resumeText; }
    public void setResumeText(String resumeText) { this.resumeText = resumeText; }

    public PipelineStatus getResumeExtractionStatus() { return resumeExtractionStatus; }
    public void setResumeExtractionStatus(PipelineStatus status) { this.resumeExtractionStatus = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Candidate{id=" + id + ", email='" + email + "', jobOpeningId=" + jobOpeningId + "}";
    }
}
