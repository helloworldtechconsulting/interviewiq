package com.interviewengine.candidate.domain;

import com.interviewengine.shared.domain.PipelineStatus;
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

    @Column(length = 30)
    private String phone;

    @Column(length = 255)
    private String googleSubject;

    @Column(length = 255)
    private String googleEmail;

    @Column(nullable = false)
    private boolean googleVerified = false;

    /**
     * Opaque identifier substituted for candidate PII in every outbound LLM
     * payload (PRD v2.1 §7.5.6). Name, email and phone are stripped and this is
     * passed instead; identity is re-attached locally when the report is
     * persisted. The evaluation model does not need to know who the candidate is
     * in order to score an answer about Spring Boot.
     */
    @Column(nullable = false, unique = true, updatable = false, length = 64)
    private String candidateRef;

    /**
     * Mints an opaque reference. Deliberately derived from a random UUID rather
     * than from the candidate's email or id: anything derivable from PII, or
     * reversible back to a database row by an outside party, would defeat the
     * point of redacting the payload in the first place.
     */
    private static String newCandidateRef() {
        return "cand_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Set when the candidate arrived through a bulk CSV import (§7.3.1). */
    private UUID importBatchId;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        // Minted here rather than in the service so that no code path can create
        // a candidate without one — the reference is what every LLM payload
        // carries in place of the candidate's identity.
        if (candidateRef == null) candidateRef = newCandidateRef();
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

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getGoogleSubject() { return googleSubject; }
    public void setGoogleSubject(String googleSubject) { this.googleSubject = googleSubject; }

    public String getGoogleEmail() { return googleEmail; }
    public void setGoogleEmail(String googleEmail) { this.googleEmail = googleEmail; }

    public boolean isGoogleVerified() { return googleVerified; }
    public void setGoogleVerified(boolean googleVerified) { this.googleVerified = googleVerified; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Candidate{id=" + id + ", email='" + email + "', jobOpeningId=" + jobOpeningId + "}";
    }

    public String getCandidateRef() { return candidateRef; }
    public void setCandidateRef(String candidateRef) { this.candidateRef = candidateRef; }

    public UUID getImportBatchId() { return importBatchId; }
    public void setImportBatchId(UUID importBatchId) { this.importBatchId = importBatchId; }
}
