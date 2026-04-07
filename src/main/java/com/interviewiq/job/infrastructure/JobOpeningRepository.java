package com.interviewiq.job.infrastructure;

import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.domain.JobStatus;
import com.interviewiq.shared.domain.PipelineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobOpeningRepository extends JpaRepository<JobOpening, UUID> {

    Optional<JobOpening> findByCompanyIdAndId(UUID companyId, UUID id);

    Page<JobOpening> findAllByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    Page<JobOpening> findAllByCompanyIdAndStatusOrderByCreatedAtDesc(UUID companyId, JobStatus status, Pageable pageable);

    /** Used by QuestionGenerationService to validate JD is ready before allowing session creation. */
    boolean existsByCompanyIdAndIdAndJdExtractionStatus(UUID companyId, UUID id, PipelineStatus status);

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
}
