package com.interviewengine.candidate.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One bulk candidate import by CSV (PRD v2.1 §7.3.1).
 *
 * <p>Moved into scope from Phase 2 for a plain reason: a recruiter running a
 * hiring drive will not add fifty candidates one at a time.
 *
 * <p>The flow is upload → column mapping → validation preview → confirm →
 * import, capped at {@link #MAX_ROWS} per import to match the per-opening limit.
 *
 * <h2>Two rules worth stating outright</h2>
 *
 * <p><strong>Validate before charging anything.</strong> The preview reports
 * exactly what will happen and lets the recruiter fix or skip rows first.
 * Duplicates are detected against existing candidates on the same opening.
 *
 * <p><strong>Reserve for the whole batch atomically.</strong> The balance is
 * checked up front and the entire import is refused with a top-up prompt rather
 * than failing partway — a 50-candidate import that runs out of money at
 * candidate 38 leaves a half-imported opening and a support ticket.
 *
 * <p>Résumés remain a separate step: files cannot come through a CSV, so
 * imported candidates without one fall back to JD-only question generation with
 * {@code resumeMissing} recorded on the session.
 */
@Entity
@Table(name = "candidate_import_batches")
public class CandidateImportBatch {

    /** Cap per import, matching the per-opening candidate limit (§7.3, §7.3.1). */
    public static final int MAX_ROWS = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID companyId;

    @Column(nullable = false, updatable = false)
    private UUID jobOpeningId;

    private UUID uploadedBy;

    @Column(nullable = false, length = 255)
    private String fileName;

    /**
     * The mapping from CSV header to candidate field that the recruiter
     * confirmed. Mapping is explicit and user-driven — the UI proposes one from
     * the header row and the user confirms or corrects it. We do not guess
     * silently, so the accepted mapping is recorded rather than re-inferred.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_mapping_jsonb", columnDefinition = "jsonb")
    private String columnMappingJson;

    @Column(nullable = false)
    private int rowCount = 0;

    @Column(nullable = false)
    private int validCount = 0;

    @Column(nullable = false)
    private int duplicateCount = 0;

    @Column(nullable = false)
    private int invalidCount = 0;

    /** The whole-batch wallet reservation, taken atomically before any row is written. */
    @Column(nullable = false)
    private long reservedAmountPaise = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportBatchStatus status = ImportBatchStatus.VALIDATING;

    /** Per-row detail shown in the preview so the recruiter can act on it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_errors_jsonb", columnDefinition = "jsonb")
    private String validationErrorsJson;

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

    /**
     * Human-readable summary of the preview, in the form the PRD specifies:
     * "47 valid, 3 duplicates, 2 invalid emails".
     */
    public String previewSummary() {
        return validCount + " valid, " + duplicateCount + " duplicates, " + invalidCount + " invalid";
    }

    /** What the whole batch will cost to reserve, at the given per-interview price. */
    public long requiredReservationPaise(long perSessionCostPaise) {
        return (long) validCount * perSessionCostPaise;
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public UUID getJobOpeningId() { return jobOpeningId; }
    public void setJobOpeningId(UUID jobOpeningId) { this.jobOpeningId = jobOpeningId; }

    public UUID getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getColumnMappingJson() { return columnMappingJson; }
    public void setColumnMappingJson(String columnMappingJson) { this.columnMappingJson = columnMappingJson; }

    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }

    public int getValidCount() { return validCount; }
    public void setValidCount(int validCount) { this.validCount = validCount; }

    public int getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(int duplicateCount) { this.duplicateCount = duplicateCount; }

    public int getInvalidCount() { return invalidCount; }
    public void setInvalidCount(int invalidCount) { this.invalidCount = invalidCount; }

    public long getReservedAmountPaise() { return reservedAmountPaise; }
    public void setReservedAmountPaise(long reservedAmountPaise) { this.reservedAmountPaise = reservedAmountPaise; }

    public ImportBatchStatus getStatus() { return status; }
    public void setStatus(ImportBatchStatus status) { this.status = status; }

    public String getValidationErrorsJson() { return validationErrorsJson; }
    public void setValidationErrorsJson(String validationErrorsJson) { this.validationErrorsJson = validationErrorsJson; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "CandidateImportBatch{id=" + id + ", status=" + status + ", " + previewSummary() + "}";
    }
}
