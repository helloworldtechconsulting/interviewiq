package com.interviewiq.candidate.infrastructure;

import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.shared.domain.PipelineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
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
}
