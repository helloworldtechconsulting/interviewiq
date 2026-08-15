package com.interviewiq.job.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.ai.infrastructure.QuestionTelemetryRepository;
import com.interviewiq.audit.annotation.Auditable;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.domain.JobStatus;
import com.interviewiq.job.dto.CreateJobRequest;
import com.interviewiq.job.dto.JdUploadUrlResponse;
import com.interviewiq.job.dto.JobResponse;
import com.interviewiq.job.dto.QuestionBankResponse;
import com.interviewiq.job.dto.UpdateJobRequest;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.shared.security.SecurityContext;
import com.interviewiq.storage.domain.StorageObjectType;
import com.interviewiq.storage.domain.UploadKind;
import com.interviewiq.storage.service.StorageObjectRecorder;
import com.interviewiq.storage.service.StorageService;
import com.interviewiq.storage.service.UploadKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private final JobOpeningRepository   jobOpeningRepository;
    private final StorageService         storageService;
    private final UploadKeyService       uploadKeyService;
    private final StorageObjectRecorder  storageObjectRecorder;
    private final QuestionTelemetryRepository telemetryRepository;
    private final ObjectMapper           objectMapper;

    public JobService(JobOpeningRepository jobOpeningRepository,
                      StorageService storageService,
                      UploadKeyService uploadKeyService,
                      StorageObjectRecorder storageObjectRecorder,
                      QuestionTelemetryRepository telemetryRepository,
                      ObjectMapper objectMapper) {
        this.jobOpeningRepository  = jobOpeningRepository;
        this.storageService        = storageService;
        this.uploadKeyService      = uploadKeyService;
        this.storageObjectRecorder = storageObjectRecorder;
        this.telemetryRepository   = telemetryRepository;
        this.objectMapper          = objectMapper;
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

    /**
     * Lists the caller's job openings, optionally narrowed by status and a
     * free-text match on title or department.
     *
     * <p>Both filters are applied by the database. A blank search string is
     * normalised to {@code null} rather than passed through as {@code ""}, because
     * {@code LIKE '%%'} matches every row including those with a null department —
     * accidentally correct here, but only by luck, and not something to rely on.
     */
    @Transactional(readOnly = true)
    public Page<JobResponse> list(JobStatus status, String search, Pageable pageable) {
        UUID companyId = SecurityContext.requireCompanyId();
        return jobOpeningRepository
                .search(companyId, status, normaliseSearch(search), pageable)
                .map(JobResponse::from);
    }

    /** Lowercases and trims a search term, or returns null when there is nothing to match. */
    private static String normaliseSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return search.strip().toLowerCase();
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
        String objectKey = uploadKeyService.deriveKey(
                UploadKind.JOB_DESCRIPTION, job.getCompanyId(), jobId, contentType);
        String uploadUrl = storageService.generatePresignedUploadUrl(objectKey, contentType, JD_UPLOAD_EXPIRY);
        return new JdUploadUrlResponse(uploadUrl, objectKey);
    }

    /**
     * Records the S3 key after the client confirms the JD upload is complete,
     * then resets extraction status to PENDING so the background worker picks it up.
     *
     * <p>The supplied key is validated against this company and job, and the stored
     * object is checked against the 10 MB ceiling and the PDF/DOCX allow-list, before
     * anything is persisted (PRD v2.1 §7.1.3).
     */
    @Transactional
    public JobResponse confirmJdUploaded(UUID jobId, String objectKey) {
        JobOpening job = requireJob(jobId);
        String ownedKey = uploadKeyService.validateOwnedKey(
                UploadKind.JOB_DESCRIPTION, job.getCompanyId(), jobId, objectKey);
        StorageService.VerifiedObject verified =
                storageService.verifyUploadedObject(ownedKey, UploadKind.JOB_DESCRIPTION);
        storageObjectRecorder.record(
                job.getCompanyId(), jobId, StorageObjectType.JOB_DESCRIPTION, ownedKey, verified);

        job.setJdS3Key(ownedKey);
        job.setJdExtractionStatus(PipelineStatus.PENDING);
        job.setJdText(null);
        jobOpeningRepository.save(job);
        log.info("JD upload confirmed: jobId={} key={}", jobId, ownedKey);
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
    // Question bank preview (PRD §11)
    // =========================================================================

    /**
     * Returns the generated question bank for an employer to read.
     *
     * <p>Read-only by design — there is no edit path here. An employer who
     * wants a specific question asked has the employer question bank
     * (§7.3.1, INTIQ-29), which is asked verbatim and marked as theirs on the
     * report. Letting them rewrite generated questions instead would put edited
     * text under the "AI-generated, safety-screened" label that
     * {@code QuestionSafetyFilter} earns, and that label needs to keep meaning
     * what it says.
     *
     * <p>A bank that has not generated yet returns an empty list with its
     * status rather than a 404 — the job exists, its bank is simply still
     * PENDING, and that is a state the UI should render rather than an error.
     */
    @Transactional(readOnly = true)
    public QuestionBankResponse questionBank(UUID jobId) {
        JobOpening job = requireJob(jobId);

        String bankJson = job.getQuestionBankJsonb();
        if (bankJson == null || bankJson.isBlank()) {
            return QuestionBankResponse.notGenerated(job.getQuestionBankStatus());
        }

        Set<String> retired = new HashSet<>(telemetryRepository.findRetiredQuestionIds(jobId));

        try {
            JsonNode root = objectMapper.readTree(bankJson);

            Set<String> coreIds = new HashSet<>();
            root.path("coreQuestionIds").forEach(n -> coreIds.add(n.asText()));

            List<QuestionBankResponse.Question> questions = new ArrayList<>();
            for (JsonNode q : root.path("questions")) {
                String id = q.path("id").asText("");
                questions.add(new QuestionBankResponse.Question(
                        id,
                        q.path("text").asText(""),
                        q.path("category").asText(""),
                        q.path("dimension").asText(""),
                        q.path("rationale").asText(""),
                        q.path("rank").asInt(0),
                        coreIds.contains(id),
                        retired.contains(id)));
            }

            int active = (int) questions.stream().filter(q -> !q.retired()).count();
            return new QuestionBankResponse(
                    job.getQuestionBankStatus(),
                    job.getQuestionBankGeneratedAt(),
                    questions.size(),
                    active,
                    questions);

        } catch (JsonProcessingException e) {
            // The bank column holds model output that passed parsing once, at
            // generation time. If it no longer parses, the honest answer is
            // "this bank is unreadable" rather than a 500 that tells the
            // employer nothing and hides which job is affected.
            log.error("Question bank for job {} is not parseable JSON", jobId, e);
            throw new ValidationException(
                    "The question bank for this job could not be read. Regenerate it to continue.");
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

}
