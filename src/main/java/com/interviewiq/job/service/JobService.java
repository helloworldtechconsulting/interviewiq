package com.interviewiq.job.service;

import com.interviewiq.audit.annotation.Auditable;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.domain.JobStatus;
import com.interviewiq.job.dto.CreateJobRequest;
import com.interviewiq.job.dto.JdUploadUrlResponse;
import com.interviewiq.job.dto.JobResponse;
import com.interviewiq.job.dto.UpdateJobRequest;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.shared.security.SecurityContext;
import com.interviewiq.storage.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Job opening lifecycle service.
 *
 * <p>All methods require an authenticated employer and enforce tenant isolation
 * by scoping every query to the caller's {@code companyId} from the JWT.
 *
 * <h2>JD upload flow</h2>
 * <ol>
 *   <li>Client calls {@link #generateJdUploadUrl} → gets a presigned S3 PUT URL.</li>
 *   <li>Client uploads the PDF/DOCX directly to S3.</li>
 *   <li>Client calls {@link #confirmJdUploaded} → sets {@code jdS3Key}, marks extraction PENDING.</li>
 *   <li>Background worker (JdExtractionService) picks up PENDING jobs and extracts text.</li>
 * </ol>
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /** JD upload URL valid for 15 minutes. */
    private static final Duration JD_UPLOAD_EXPIRY = Duration.ofMinutes(15);

    private final JobOpeningRepository jobOpeningRepository;
    private final StorageService       storageService;

    public JobService(JobOpeningRepository jobOpeningRepository,
                      StorageService storageService) {
        this.jobOpeningRepository = jobOpeningRepository;
        this.storageService       = storageService;
    }

    // =========================================================================
    // CRUD
    // =========================================================================

    @Auditable(action = "JOB_CREATED", entityType = "JOB")
    @Transactional
    public JobResponse create(CreateJobRequest req) {
        UUID companyId = SecurityContext.requireCompanyId();
        UUID userId    = SecurityContext.requireUserId();

        JobOpening job = new JobOpening();
        job.setCompanyId(companyId);
        job.setCreatedBy(userId);
        job.setTitle(req.title().strip());
        job.setDepartment(req.department() != null ? req.department().strip() : null);
        job.setLocationType(req.locationType());
        job.setEmploymentType(req.employmentType());
        job.setDescription(req.description());
        job.setExperienceMin(req.experienceMin());
        job.setExperienceMax(req.experienceMax());
        job.setStatus(JobStatus.ACTIVE);
        job.setJdExtractionStatus(PipelineStatus.PENDING);
        jobOpeningRepository.save(job);

        log.info("Job created: companyId={} jobId={} title={}", companyId, job.getId(), job.getTitle());
        return JobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public Page<JobResponse> list(Pageable pageable) {
        UUID companyId = SecurityContext.requireCompanyId();
        return jobOpeningRepository
                .findAllByCompanyIdOrderByCreatedAtDesc(companyId, pageable)
                .map(JobResponse::from);
    }

    @Transactional(readOnly = true)
    public JobResponse get(UUID jobId) {
        return JobResponse.from(requireJob(jobId));
    }

    @Auditable(action = "JOB_UPDATED", entityType = "JOB", entityIdArg = 0)
    @Transactional
    public JobResponse update(UUID jobId, UpdateJobRequest req) {
        JobOpening job = requireJob(jobId);

        if (req.title() != null)          job.setTitle(req.title().strip());
        if (req.department() != null)     job.setDepartment(req.department().strip());
        if (req.locationType() != null)   job.setLocationType(req.locationType());
        if (req.employmentType() != null) job.setEmploymentType(req.employmentType());
        if (req.status() != null)         job.setStatus(req.status());
        if (req.description() != null)    job.setDescription(req.description());
        if (req.experienceMin() != null)  job.setExperienceMin(req.experienceMin());
        if (req.experienceMax() != null)  job.setExperienceMax(req.experienceMax());

        jobOpeningRepository.save(job);
        return JobResponse.from(job);
    }

    @Auditable(action = "JOB_DELETED", entityType = "JOB", entityIdArg = 0)
    @Transactional
    public void delete(UUID jobId) {
        JobOpening job = requireJob(jobId);
        // Soft-delete: mark CLOSED
        job.setStatus(JobStatus.CLOSED);
        jobOpeningRepository.save(job);
        log.info("Job closed: companyId={} jobId={}", job.getCompanyId(), jobId);
    }

    // =========================================================================
    // JD upload
    // =========================================================================

    /**
     * Generates a presigned S3 PUT URL for uploading the job description file.
     * The caller must use {@code Content-Type: application/pdf} or
     * {@code application/vnd.openxmlformats-officedocument.wordprocessingml.document}.
     */
    public JdUploadUrlResponse generateJdUploadUrl(UUID jobId, String contentType) {
        JobOpening job = requireJob(jobId);
        String objectKey = "jd/" + job.getCompanyId() + "/" + jobId + "/" +
                           UUID.randomUUID() + resolveExtension(contentType);
        String uploadUrl = storageService.generatePresignedUploadUrl(objectKey, contentType, JD_UPLOAD_EXPIRY);
        return new JdUploadUrlResponse(uploadUrl, objectKey);
    }

    /**
     * Records the S3 key after the client confirms the JD upload is complete,
     * then resets extraction status to PENDING so the background worker picks it up.
     */
    @Transactional
    public JobResponse confirmJdUploaded(UUID jobId, String objectKey) {
        JobOpening job = requireJob(jobId);
        if (objectKey == null || objectKey.isBlank()) {
            throw new ValidationException("Object key must not be blank.");
        }
        job.setJdS3Key(objectKey);
        job.setJdExtractionStatus(PipelineStatus.PENDING);
        job.setJdText(null);
        jobOpeningRepository.save(job);
        log.info("JD upload confirmed: jobId={} key={}", jobId, objectKey);
        return JobResponse.from(job);
    }

    // =========================================================================
    // Package-visible helpers (used by other services)
    // =========================================================================

    public JobOpening requireJob(UUID jobId) {
        UUID companyId = SecurityContext.requireCompanyId();
        return jobOpeningRepository.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new ResourceNotFoundException("JobOpening", jobId));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private String resolveExtension(String contentType) {
        if (contentType == null) return "";
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            default -> "";
        };
    }
}
