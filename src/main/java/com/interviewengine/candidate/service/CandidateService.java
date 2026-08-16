package com.interviewengine.candidate.service;

import com.interviewengine.candidate.domain.Candidate;
import com.interviewengine.candidate.dto.CandidateResponse;
import com.interviewengine.candidate.dto.CreateCandidateRequest;
import com.interviewengine.candidate.dto.ResumeUploadUrlResponse;
import com.interviewengine.candidate.dto.UpdateCandidateRequest;
import com.interviewengine.candidate.infrastructure.CandidateRepository;
import com.interviewengine.job.infrastructure.JobOpeningRepository;
import com.interviewengine.session.domain.SessionStatus;
import com.interviewengine.session.infrastructure.InterviewSessionRepository;
import com.interviewengine.shared.domain.PipelineStatus;
import com.interviewengine.shared.exception.ConflictException;
import com.interviewengine.shared.exception.ResourceNotFoundException;
import com.interviewengine.shared.exception.ValidationException;
import com.interviewengine.shared.security.SecurityContext;
import com.interviewengine.storage.domain.StorageObjectType;
import com.interviewengine.storage.domain.UploadKind;
import com.interviewengine.storage.service.StorageObjectRecorder;
import com.interviewengine.storage.service.StorageService;
import com.interviewengine.storage.service.UploadKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Candidate lifecycle service.
 *
 * <p>A candidate is always created within the context of a {@link com.interviewengine.job.domain.JobOpening}.
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

    /**
     * Session states that hold a wallet reservation, capacity, or both, and so
     * must be resolved before the candidate they belong to can be removed.
     */
    private static final List<SessionStatus> IN_FLIGHT_STATUSES = List.of(
            SessionStatus.INVITED,
            SessionStatus.SCHEDULED,
            SessionStatus.IN_PROGRESS,
            SessionStatus.EVALUATING);

    private final CandidateRepository    candidateRepository;
    private final JobOpeningRepository   jobOpeningRepository;
    private final StorageService         storageService;
    private final UploadKeyService       uploadKeyService;
    private final StorageObjectRecorder  storageObjectRecorder;
    private final InterviewSessionRepository sessionRepository;

    public CandidateService(CandidateRepository candidateRepository,
                            JobOpeningRepository jobOpeningRepository,
                            StorageService storageService,
                            UploadKeyService uploadKeyService,
                            StorageObjectRecorder storageObjectRecorder,
                            InterviewSessionRepository sessionRepository) {
        this.candidateRepository   = candidateRepository;
        this.jobOpeningRepository  = jobOpeningRepository;
        this.storageService        = storageService;
        this.uploadKeyService      = uploadKeyService;
        this.storageObjectRecorder = storageObjectRecorder;
        this.sessionRepository     = sessionRepository;
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

    /**
     * Lists candidates with optional job, résumé-status and free-text filters,
     * all evaluated in the database.
     *
     * <p>When a job filter is supplied it is still verified to belong to the
     * caller's company before the query runs. Scoping the query by {@code
     * companyId} would already make a foreign job return nothing, but "no results"
     * and "not yours" are different answers and the caller deserves the accurate
     * one.
     */
    @Transactional(readOnly = true)
    public Page<CandidateResponse> search(UUID jobOpeningId,
                                          PipelineStatus resumeStatus,
                                          String search,
                                          Pageable pageable) {
        UUID companyId = SecurityContext.requireCompanyId();

        if (jobOpeningId != null) {
            jobOpeningRepository.findByCompanyIdAndId(companyId, jobOpeningId)
                    .orElseThrow(() -> new ResourceNotFoundException("JobOpening", jobOpeningId));
        }

        String term = (search == null || search.isBlank()) ? null : search.strip().toLowerCase();
        return candidateRepository
                .search(companyId, jobOpeningId, resumeStatus, term, pageable)
                .map(CandidateResponse::from);
    }

    @Transactional(readOnly = true)
    public CandidateResponse get(UUID candidateId) {
        return CandidateResponse.from(requireCandidate(candidateId));
    }

    /**
     * Updates a candidate's details, but only while they have never been invited.
     *
     * <p>The guard is "no session exists", not "no active session". Once an invite
     * has gone out, the candidate has an email in their inbox addressed to a
     * particular name, and a booking link tied to this record. Editing the name
     * afterwards produces a report that disagrees with the invitation the person
     * actually received — and if the email is changed, the already-sent link still
     * works while the new address never receives anything. Refusing is clearer
     * than either outcome.
     */
    @Transactional
    public CandidateResponse update(UUID candidateId, UpdateCandidateRequest req) {
        Candidate candidate = requireCandidate(candidateId);

        if (sessionRepository.existsByCandidateId(candidateId)) {
            throw new ConflictException(
                    "This candidate has already been invited, so their details can no longer be edited. "
                            + "Cancel the interview and add them again if the details were wrong.");
        }

        if (req.fullName() != null && !req.fullName().isBlank()) {
            candidate.setFullName(req.fullName().strip());
        }

        if (req.email() != null && !req.email().isBlank()) {
            String newEmail = req.email().toLowerCase().strip();
            if (!newEmail.equals(candidate.getEmail())
                    && candidateRepository.existsByJobOpeningIdAndEmail(candidate.getJobOpeningId(), newEmail)) {
                throw new ConflictException(
                        "A candidate with email '" + newEmail + "' already exists for this job opening.");
            }
            candidate.setEmail(newEmail);
        }

        if (req.phone() != null) {
            candidate.setPhone(req.phone().isBlank() ? null : req.phone().strip());
        }

        candidateRepository.save(candidate);
        log.info("Candidate updated: candidateId={}", candidateId);
        return CandidateResponse.from(candidate);
    }

    /**
     * Deletes a candidate that has no interview history.
     *
     * <p>Two separate refusals, for two different reasons:
     *
     * <ul>
     *   <li><strong>A completed session exists.</strong> There is an evaluation
     *       report attached to this person, and a report whose candidate has been
     *       deleted is worse than useless — it is an unattributable hiring
     *       assessment. This is the refusal the story asked for.</li>
     *   <li><strong>A session is still in flight.</strong> Not in the story, but it
     *       matters more in practice: an {@code INVITED} or {@code SCHEDULED}
     *       session is holding a ₹100 wallet reservation and, if scheduled,
     *       capacity buckets. Deleting the candidate would strand both with nothing
     *       left pointing at them to release them. Cancelling the session first
     *       releases the money and the capacity, and is what the caller should do.</li>
     * </ul>
     */
    @Transactional
    public void delete(UUID candidateId) {
        Candidate candidate = requireCandidate(candidateId);

        if (sessionRepository.existsByCandidateIdAndStatus(candidateId, SessionStatus.COMPLETED)) {
            throw new ConflictException(
                    "This candidate has a completed interview and an evaluation report, so they cannot be deleted.");
        }

        if (sessionRepository.existsByCandidateIdAndStatusIn(candidateId, IN_FLIGHT_STATUSES)) {
            throw new ConflictException(
                    "This candidate has an interview in progress or scheduled. "
                            + "Cancel it first — that releases the reserved ₹100 and the booked slot.");
        }

        candidateRepository.delete(candidate);
        log.info("Candidate deleted: companyId={} candidateId={}", candidate.getCompanyId(), candidateId);
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
        StorageService.VerifiedObject verified =
                storageService.verifyUploadedObject(ownedKey, UploadKind.RESUME);
        storageObjectRecorder.record(
                candidate.getCompanyId(), candidateId, StorageObjectType.RESUME, ownedKey, verified);

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
