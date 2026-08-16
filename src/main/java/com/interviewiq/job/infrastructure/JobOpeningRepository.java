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

    /** Dashboard counter — a COUNT, not a page of rows whose envelope gets read (INTIQ-73). */
    long countByCompanyIdAndStatus(UUID companyId, JobStatus status);

    /**
     * Claims openings whose JD is extracted but whose question bank is not yet
     * generated (INTIQ-17, V056).
     *
     * <p>Claimed with {@code FOR UPDATE SKIP LOCKED} like every other worker
     * (§7.9), and the reason is sharper here than for most. Duplicate evaluation
     * wastes money and produces a non-deterministic score; duplicate <em>bank</em>
     * generation silently changes which questions are core, so two candidates
     * interviewed minutes apart would be scored against different fixed sets —
     * defeating the one property the core exists to provide.
     *
     * <p>The staleness clause reclaims banks abandoned by a pod that died
     * mid-generation, rather than leaving the opening permanently unable to
     * invite anyone.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE job_openings
           SET question_bank_status   = 'IN_PROGRESS',
               question_bank_attempts = question_bank_attempts + 1,
               question_bank_claimed_at = now(),
               updated_at             = now()
           WHERE id IN (
               SELECT id FROM job_openings
               WHERE jd_extraction_status = 'DONE'
                 AND status = 'ACTIVE'
                 AND question_bank_attempts < :maxAttempts
                 AND (question_bank_status = 'PENDING'
                      OR (question_bank_status = 'IN_PROGRESS' AND question_bank_claimed_at < :staleBefore))
               ORDER BY created_at ASC
               LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
           )
           RETURNING *
           """, nativeQuery = true)
    List<JobOpening> claimForQuestionBank(@Param("batchSize") int batchSize,
                                          @Param("staleBefore") OffsetDateTime staleBefore,
                                          @Param("maxAttempts") int maxAttempts);

    /** Pending bank generation, for the KEDA scaler and the readiness alarm. */
    @Query(value = """
           SELECT COUNT(*) FROM job_openings
           WHERE jd_extraction_status = 'DONE'
             AND question_bank_status IN ('PENDING', 'IN_PROGRESS')
           """, nativeQuery = true)
    long countPendingQuestionBanks();

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
    /*
     * CAST(:search AS String) is load-bearing, not decoration.
     *
     * Without it, a null search — which is the DEFAULT for this endpoint, since
     * the filter is optional — reaches PostgreSQL as an untyped bind parameter.
     * PostgreSQL cannot infer a type for it from LIKE alone, guesses bytea, and
     * the statement dies with:
     *
     *     ERROR: operator does not exist: text ~~ bytea
     *
     * The result was a 500 on GET /api/v1/jobs with no filters applied, which is
     * the first request the jobs page makes. The cast gives the parameter an
     * explicit varchar type so the comparison resolves.
     */
    @Query("""
           SELECT j FROM JobOpening j
           WHERE j.companyId = :companyId
             AND (:status IS NULL OR j.status = :status)
             AND (CAST(:search AS String) IS NULL
                  OR LOWER(j.title)      LIKE CONCAT('%', CAST(:search AS String), '%')
                  OR LOWER(j.department) LIKE CONCAT('%', CAST(:search AS String), '%'))
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
