package com.interviewengine.candidate.infrastructure;

import com.interviewengine.candidate.domain.Candidate;
import com.interviewengine.shared.domain.PipelineStatus;
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

public interface CandidateRepository extends JpaRepository<Candidate, UUID> {

    Optional<Candidate> findByCompanyIdAndId(UUID companyId, UUID id);

    Page<Candidate> findAllByJobOpeningIdOrderByCreatedAtDesc(UUID jobOpeningId, Pageable pageable);

    /** Company-wide listing — used when no jobOpeningId filter is supplied. */
    Page<Candidate> findAllByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    boolean existsByJobOpeningIdAndEmail(UUID jobOpeningId, String email);

    /** Dashboard counter (INTIQ-73). */
    long countByCompanyId(UUID companyId);

    /**
     * Candidate listing with optional job, résumé-status and free-text filters,
     * evaluated in the database.
     *
     * <p>Same defect as the job list: the page previously searched only the rows
     * it had already fetched, so a candidate on page two was invisible to a search
     * that should have found them. On a bulk import of 200 candidates that is the
     * normal case rather than an edge case.
     *
     * <p>{@code search} matches name or email; all three parameters are nullable.
     */
    /*
     * CAST(:search AS String) for the same reason as JobOpeningRepository.search:
     * a null bind used with LIKE is untyped, PostgreSQL guesses bytea, and the
     * query fails with "operator does not exist: text ~~ bytea". Null search is
     * the default on GET /api/v1/candidates, so this was a 500 on the candidates
     * page's first request.
     */
    @Query("""
           SELECT c FROM Candidate c
           WHERE c.companyId = :companyId
             AND (:jobOpeningId IS NULL OR c.jobOpeningId = :jobOpeningId)
             AND (:status IS NULL OR c.resumeExtractionStatus = :status)
             AND (CAST(:search AS String) IS NULL
                  OR LOWER(c.fullName) LIKE CONCAT('%', CAST(:search AS String), '%')
                  OR LOWER(c.email)    LIKE CONCAT('%', CAST(:search AS String), '%'))
           ORDER BY c.createdAt DESC
           """)
    Page<Candidate> search(@Param("companyId") UUID companyId,
                           @Param("jobOpeningId") UUID jobOpeningId,
                           @Param("status") PipelineStatus status,
                           @Param("search") String search,
                           Pageable pageable);

    /** Candidates with a resume uploaded but text extraction not yet started — for ResumeExtractionWorker. */
    List<Candidate> findAllByResumeExtractionStatusAndResumeS3KeyIsNotNull(PipelineStatus status);

    /**
     * Candidates to process — includes PENDING (normal) and IN_PROGRESS (crash recovery).
     * Safe to include IN_PROGRESS because fixedDelay prevents concurrent scheduler runs.
     */
    List<Candidate> findAllByResumeExtractionStatusInAndResumeS3KeyIsNotNull(Collection<PipelineStatus> statuses);

    Optional<Candidate> findByGoogleSubject(String googleSubject);

    /**
     * Atomically claims candidates whose résumé text needs extracting.
     * Same {@code FOR UPDATE SKIP LOCKED} discipline as every other poller (§7.9).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE candidates
           SET resume_extraction_status     = 'IN_PROGRESS',
               resume_extraction_claimed_at = now(),
               updated_at                   = now()
           WHERE id IN (
               SELECT id FROM candidates
               WHERE resume_s3_key IS NOT NULL
                 AND (resume_extraction_status = 'PENDING'
                      OR (resume_extraction_status = 'IN_PROGRESS' AND resume_extraction_claimed_at < :staleBefore))
               ORDER BY created_at ASC
               LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
           )
           RETURNING *
           """, nativeQuery = true)
    List<Candidate> claimForResumeExtraction(@Param("batchSize") int batchSize,
                                             @Param("staleBefore") OffsetDateTime staleBefore);

    /**
     * Emails already on an opening, for duplicate detection during a bulk import
     * (PRD v2.1 §7.3.1). Projected rather than loading entities: a 200-candidate
     * opening is 200 rows of which only one column matters.
     */
    @Query("SELECT c.email FROM Candidate c WHERE c.jobOpeningId = :jobOpeningId")
    List<String> findAllEmailsByJobOpeningId(@Param("jobOpeningId") UUID jobOpeningId);
}
