package com.interviewiq.candidate.infrastructure;

import com.interviewiq.candidate.domain.Candidate;
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

public interface CandidateRepository extends JpaRepository<Candidate, UUID> {

    Optional<Candidate> findByCompanyIdAndId(UUID companyId, UUID id);

    Page<Candidate> findAllByJobOpeningIdOrderByCreatedAtDesc(UUID jobOpeningId, Pageable pageable);

    /** Company-wide listing — used when no jobOpeningId filter is supplied. */
    Page<Candidate> findAllByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    boolean existsByJobOpeningIdAndEmail(UUID jobOpeningId, String email);

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
