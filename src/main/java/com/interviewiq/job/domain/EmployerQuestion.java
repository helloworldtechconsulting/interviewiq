package com.interviewiq.job.domain;

import com.interviewiq.shared.exception.ValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A question the employer supplied against a job opening (PRD v2.1 §7.5.8).
 *
 * <p>Optional — the default remains 100% AI-generated. Where employers use it, it
 * is the most-requested control from recruiters who have a house question they
 * always ask, and it slots into the two-stage generation design already
 * specified: employer questions occupy the <em>core segment</em> first,
 * guaranteeing every candidate for the job is asked them and stays comparable,
 * and the AI fills the remainder to reach the tier's question count while
 * preserving the 80/20 JD-to-résumé ratio.
 *
 * <p>Employer questions are always included and never sampled out — the employer
 * asked for them because they care about them. If more are supplied than the tier
 * holds, the extras rotate across candidates in {@link #displayOrder}.
 *
 * @see QuestionSafetyStatus for the non-negotiable safety rule
 */
@Entity
@Table(name = "employer_questions")
public class EmployerQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID companyId;

    @Column(nullable = false, updatable = false)
    private UUID jobOpeningId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionSafetyStatus safetyStatus = QuestionSafetyStatus.PENDING;

    /** Set only on rejection; names the prohibited category. */
    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(nullable = false)
    private int displayOrder = 0;

    private UUID createdBy;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Whether this question may be placed in a session's question bank. */
    public boolean isUsable() {
        return safetyStatus == QuestionSafetyStatus.APPROVED;
    }

    /** Records that the question cleared the prohibited-topic filter. */
    public void approve() {
        this.safetyStatus = QuestionSafetyStatus.APPROVED;
        this.rejectionReason = null;
    }

    /**
     * Records a refusal.
     *
     * @param category the prohibited category the question touches — named
     *                 explicitly so the employer can correct it, per §7.5.8
     * @throws ValidationException if no category is given; a rejection the
     *         employer cannot act on is worse than none
     */
    public void reject(String category) {
        if (category == null || category.isBlank()) {
            throw new ValidationException("A rejection must name the prohibited category.");
        }
        this.safetyStatus = QuestionSafetyStatus.REJECTED;
        this.rejectionReason = category;
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public UUID getJobOpeningId() { return jobOpeningId; }
    public void setJobOpeningId(UUID jobOpeningId) { this.jobOpeningId = jobOpeningId; }

    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }

    public QuestionSafetyStatus getSafetyStatus() { return safetyStatus; }
    public void setSafetyStatus(QuestionSafetyStatus safetyStatus) { this.safetyStatus = safetyStatus; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "EmployerQuestion{id=" + id + ", jobOpeningId=" + jobOpeningId
                + ", safetyStatus=" + safetyStatus + "}";
    }
}
