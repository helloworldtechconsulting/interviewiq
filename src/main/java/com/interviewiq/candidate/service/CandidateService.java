package com.interviewiq.candidate.service;

import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.dto.CandidateResponse;
import com.interviewiq.candidate.dto.CreateCandidateRequest;
import com.interviewiq.candidate.dto.ResumeUploadUrlResponse;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.shared.exception.ConflictException;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.shared.security.SecurityContext;
import com.interviewiq.storage.domain.UploadKind;
import com.interviewiq.storage.service.StorageService;
import com.interviewiq.storage.service.UploadKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Candidate lifecycle service.
 *
 * <p>A candidate is always created within the context of a {@link com.interviewiq.job.domain.JobOpening}.
 * The same email may appear in multiple job openings but not twice in the same one
 * (enforced by the unique constraint on {@code (job_opening_id, email)}).
 *
 * <h2>Resume upload flow</h2>
 * <ol>
 *   <li>Client calls {@link #generateResumeUploadUrl} → gets a presigned S3 PUT URL.</li>
 *   <li>Client uploads PDF/DOCX directly to S3.</li>
 *   <li>Client calls {@link #confirmResumeUploaded} → sets {@code resumeS3Key}, marks extraction PENDING.</li>
 *   <li>Background worker extracts resume text asynchronously.</li>
 * </ol>
 */
@Service
public class CandidateService {

    private static final Logger log = LoggerFactory.getLogger(CandidateService.class);

    /** Resume upload URL valid for 15 minutes. */
    private static final Duration RESUME_UPLOAD_EXPIRY = Duration.ofMinutes(15);

    private final CandidateRepository    candidateRepository;
    private final JobOpeningRepository   jobOpeningRepository;
    private final StorageService         storageService;
    private final UploadKeyService       uploadKeyService;

    public CandidateService(CandidateRepository candidateRepository,
                            JobOpeningRepository jobOpeningRepository,
                            StorageService storageService,
                            UploadKeyService uploadKeyService) {
        this.candidateRepository  = candidateRepository;
        this.jobOpeningRepository = jobOpeningRepository;
        this.storageService       = storageService;
        this.uploadKeyService     = uploadKeyService;
    }

    // =========================================================================
    // CRUD
    // =========================================================================

    @Transactional
    public CandidateResponse create(CreateCandidateRequest req) {
        UUID companyId = SecurityContext.requireCompanyId();
        String email   = req.email().toLowerCase().strip();

        // Verify the job opening belongs to this company
        jobOpeningRepository.findByCompanyIdAndId(companyId, req.jobOpeningId())
                .orElseThrow(() -> new ResourceNotFoundException("JobOpening", req.jobOpeningId()));

        if (candidateRepository.existsByJobOpeningIdAndEmail(req.jobOpeningId(), email)) {
            throw new ConflictException(
                    "A candidate with email '" + email + "' already exists for this job opening.");
        }

        Candidate candidate = new Candidate();
        candidate.setCompanyId(companyId);
        candidate.setJobOpeningId(req.jobOpeningId());
        candidate.setEmail(email);
        candidate.setFullName(req.fullName().strip());
        if (req.phone() != null && !req.phone().isBlank()) {
            candidate.setPhone(req.phone().strip());
        }
        candidate.setResumeExtractionStatus(PipelineStatus.PENDING);
        candidateRepository.save(candidate);

        log.info("Candidate created: companyId={} candidateId={} email={}", companyId, candidate.getId(), email);
        return CandidateResponse.from(candidate);
    }

    @Transactional(readOnly = true)
    public Page<CandidateResponse> listByJob(UUID jobOpeningId, Pageable pageable) {
        UUID companyId = SecurityContext.requireCompanyId();
        // Verify job opening belongs to this company
        jobOpeningRepository.findByCompanyIdAndId(companyId, jobOpeningId)
                .orElseThrow(() -> new ResourceNotFoundException("JobOpening", jobOpeningId));

        return candidateRepository
                .findAllByJobOpeningIdOrderByCreatedAtDesc(jobOpeningId, pageable)
                .map(CandidateResponse::from);
    }

    /** Lists all candidates for the caller's company — no job filter. */
    @Transactional(readOnly = true)
    public Page<CandidateResponse> listAll(Pageable pageable) {
        UUID companyId = SecurityContext.requireCompanyId();
        return candidateRepository
                .findAllByCompanyIdOrderByCreatedAtDesc(companyId, pageable)
                .map(CandidateResponse::from);
    }

    @Transactional(readOnly = true)
    public CandidateResponse get(UUID candidateId) {
        return CandidateResponse.from(requireCandidate(candidateId));
    }

    // =========================================================================
    // Resume upload
    // =========================================================================

    /**
     * Generates a presigned S3 PUT URL for uploading the candidate's resume.
     *
     * <p>The key is derived server-side from the authenticated company and this
     * candidate; the content type is checked against the résumé allow-list (PDF or
     * DOCX) before a URL is issued.
     */
    public ResumeUploadUrlResponse generateResumeUploadUrl(UUID candidateId, String contentType) {
        Candidate candidate = requireCandidate(candidateId);
        String objectKey = uploadKeyService.deriveKey(
                UploadKind.RESUME, candidate.getCompanyId(), candidateId, contentType);
        String uploadUrl = storageService.generatePresignedUploadUrl(objectKey, contentType, RESUME_UPLOAD_EXPIRY);
        return new ResumeUploadUrlResponse(uploadUrl, objectKey);
    }

    /**
     * Records the S3 key after the client confirms the resume upload is complete.
     *
     * <p>The supplied key is validated against this company and candidate, and the
     * object itself is inspected for size and MIME conformance, before anything is
     * persisted. A pre-signed PUT cannot bound the body size, so the real file has
     * to be checked here rather than trusted (PRD v2.1 §7.1.3).
     */
    @Transactional
    public CandidateResponse confirmResumeUploaded(UUID candidateId, String objectKey) {
        Candidate candidate = requireCandidate(candidateId);
        String ownedKey = uploadKeyService.validateOwnedKey(
                UploadKind.RESUME, candidate.getCompanyId(), candidateId, objectKey);
        storageService.verifyUploadedObject(ownedKey, UploadKind.RESUME);

        candidate.setResumeS3Key(ownedKey);
        candidate.setResumeExtractionStatus(PipelineStatus.PENDING);
        candidate.setResumeText(null);
        candidateRepository.save(candidate);
        log.info("Resume upload confirmed: candidateId={} key={}", candidateId, ownedKey);
        return CandidateResponse.from(candidate);
    }

    // =========================================================================
    // Package-visible helpers (used by SessionService)
    // =========================================================================

    public Candidate requireCandidate(UUID candidateId) {
        UUID companyId = SecurityContext.requireCompanyId();
        return candidateRepository.findByCompanyIdAndId(companyId, candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate", candidateId));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

}
