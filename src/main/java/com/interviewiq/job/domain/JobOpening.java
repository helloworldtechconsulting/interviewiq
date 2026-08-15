package com.interviewiq.job.domain;

import com.interviewiq.shared.domain.PipelineStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A job position for which the company is actively recruiting.
 *
 * <p>Session creation is BLOCKED until {@code jdExtractionStatus == DONE}.
 * The JD text ({@code jdText}) is the primary AI pipeline input for question
 * generation; without it, questions cannot be personalised to the role.
 *
 * <p>DB table: {@code job_openings} (V005)
 */
@Entity
@Table(name = "job_openings")
public class JobOpening {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK → companies(id). Immutable after creation. */
    @Column(nullable = false, updatable = false)
    private UUID companyId;

    /**
     * FK → users(id) via composite FK (company_id, created_by).
     * DB enforces this user belongs to the same company.
     */
    @Column(nullable = false, updatable = false)
    private UUID createdBy;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 255)
    private String department;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private LocationType locationType;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private EmploymentType employmentType;

    /** S3 object key for the original uploaded JD file (PDF/DOCX). */
    @Column(name = "jd_s3_key", length = 512)
    private String jdS3Key;

    /**
     * Extracted plain-text content of the JD. Null while extraction is in progress.
     * Populated asynchronously by Apache Tika via {@code JdExtractionService}.
     */
    @Column(columnDefinition = "TEXT")
    private String jdText;

    /**
     * Tracks the async Tika extraction pipeline state.
     * Session creation is blocked until this reaches {@link PipelineStatus#DONE}.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PipelineStatus jdExtractionStatus = PipelineStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "experience_min")
    private Integer experienceMin;

    @Column(name = "experience_max")
    private Integer experienceMax;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "questions_jsonb", columnDefinition = "jsonb")
    private String questionsJsonb;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private JobStatus status = JobStatus.ACTIVE;

    /**
     * How long interviews for this opening run, and therefore how many questions
     * are generated and how many capacity buckets a booking occupies
     * (PRD v2.1 §7.2.1). Defaults to Standard.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DurationTier durationTier = DurationTier.defaultTier();

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

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public LocationType getLocationType() { return locationType; }
    public void setLocationType(LocationType locationType) { this.locationType = locationType; }

    public EmploymentType getEmploymentType() { return employmentType; }
    public void setEmploymentType(EmploymentType employmentType) { this.employmentType = employmentType; }

    public String getJdS3Key() { return jdS3Key; }
    public void setJdS3Key(String jdS3Key) { this.jdS3Key = jdS3Key; }

    public String getJdText() { return jdText; }
    public void setJdText(String jdText) { this.jdText = jdText; }

    public PipelineStatus getJdExtractionStatus() { return jdExtractionStatus; }
    public void setJdExtractionStatus(PipelineStatus jdExtractionStatus) { this.jdExtractionStatus = jdExtractionStatus; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getExperienceMin() { return experienceMin; }
    public void setExperienceMin(Integer experienceMin) { this.experienceMin = experienceMin; }

    public Integer getExperienceMax() { return experienceMax; }
    public void setExperienceMax(Integer experienceMax) { this.experienceMax = experienceMax; }

    public String getQuestionsJsonb() { return questionsJsonb; }
    public void setQuestionsJsonb(String questionsJsonb) { this.questionsJsonb = questionsJsonb; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "JobOpening{id=" + id + ", companyId=" + companyId + ", title='" + title + "'}";
    }

    public DurationTier getDurationTier() { return durationTier; }
    public void setDurationTier(DurationTier durationTier) { this.durationTier = durationTier; }
}
