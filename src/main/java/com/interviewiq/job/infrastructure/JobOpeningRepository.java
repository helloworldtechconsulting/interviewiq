package com.interviewiq.job.infrastructure;

import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.domain.JobStatus;
import com.interviewiq.shared.domain.PipelineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobOpeningRepository extends JpaRepository<JobOpening, UUID> {

    Optional<JobOpening> findByCompanyIdAndId(UUID companyId, UUID id);

    Page<JobOpening> findAllByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    Page<JobOpening> findAllByCompanyIdAndStatusOrderByCreatedAtDesc(UUID companyId, JobStatus status, Pageable pageable);

    /** Used by QuestionGenerationService to validate JD is ready before allowing session creation. */
    boolean existsByCompanyIdAndIdAndJdExtractionStatus(UUID companyId, UUID id, PipelineStatus status);

    /**
     * Company job listing with optional status and free-text filters, applied in
     * the database rather than in the browser.
     *
     * <p>The UI previously filtered the <em>current page</em> client-side, which
     * looks like search and is not: a title on page three simply never appears,
     * and the emptier the match the more wrong the result. Both parameters are
     * nullable so one query serves all four combinations without the caller
     * choosing a method name.
     *
     * <p>{@code search} matches title or department, case-insensitively. It is
     * lowercased here rather than at the call site so the {@code LOWER()} on both
     * sides stays visible next to the index that would need to support it.
     */
    @Query("""
           SELECT j FROM JobOpening j
           WHERE j.companyId = :companyId
             AND (:status IS NULL OR j.status = :status)
             AND (:search IS NULL
                  OR LOWER(j.title)      LIKE CONCAT('%', :search, '%')
                  OR LOWER(j.department) LIKE CONCAT('%', :search, '%'))
           ORDER BY j.createdAt DESC
           """)
    Page<JobOpening> search(@Param("companyId") UUID companyId,
                            @Param("status") JobStatus status,
                            @Param("search") String search,
                            Pageable pageable);

    /** Active jobs a company can create sessions against. */
    List<JobOpening> findAllByCompanyIdAndStatus(UUID companyId, JobStatus status);

    /** Jobs with a file uploaded but text extraction not yet started — for JdExtractionWorker. */
    List<JobOpening> findAllByJdExtractionStatusAndJdS3KeyIsNotNull(PipelineStatus status);

    /**
     * Jobs to process for JD extraction — includes both PENDING (normal path) and
     * IN_PROGRESS (recovery path for items orphaned by a previous JVM crash).
     * Since the @Scheduled fixedDelay prevents concurrent runs, IN_PROGRESS items
     * from the current run are always resolved before the next poll starts.
     */
    List<JobOpening> findAllByJdExtractionStatusInAndJdS3KeyIsNotNull(Collection<PipelineStatus> statuses);

    /**
     * Atomically claims job openings whose JD text needs extracting.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} with an explicit {@code LIMIT}, per
     * PRD v2.1 §7.9. Tika extraction is cheap compared with an LLM call, but
     * duplicate extraction still means duplicate object-storage reads and racing
     * writes to the same jd_text — and there is no reason for this to be the one
     * worker that ignores the discipline.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE job_openings
           SET jd_extraction_status   = 'IN_PROGRESS',
               jd_extraction_claimed_at = now(),
               updated_at             = now()
           WHERE id IN (
               SELECT id FROM job_openings
               WHERE jd_s3_key IS NOT NULL
                 AND (jd_extraction_status = 'PENDING'
                      OR (jd_extraction_status = 'IN_PROGRESS' AND jd_extraction_claimed_at < :staleBefore))
               ORDER BY created_at ASC
               LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
           )
           RETURNING *
           """, nativeQuery = true)
    List<JobOpening> claimForJdExtraction(@Param("batchSize") int batchSize,
                                          @Param("staleBefore") OffsetDateTime staleBefore);
}
