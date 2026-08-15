package com.interviewiq.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interviewiq.auth.service.TokenService;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.email.service.EmailService;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.dto.CompleteInterviewRequest;
import com.interviewiq.session.dto.CreateSessionRequest;
import com.interviewiq.session.dto.EvaluationReportResponse;
import com.interviewiq.session.dto.InterviewInitResponse;
import com.interviewiq.session.dto.SessionResponse;
import com.interviewiq.session.infrastructure.EvaluationReportRepository;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.audit.annotation.Auditable;
import com.interviewiq.auth.config.SecurityProperties;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.SessionStateException;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.shared.security.CandidatePrincipal;
import com.interviewiq.shared.security.SecurityContext;
import com.interviewiq.storage.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Interview session lifecycle service.
 *
 * <h2>Create flow</h2>
 * <ol>
 *   <li>Validate job opening belongs to company and JD is extracted (DONE).</li>
 *   <li>Validate candidate belongs to the same job opening.</li>
 *   <li>Persist the session row to obtain the real session ID.</li>
 *   <li>Reserve billing funds using the real session ID.</li>
 *   <li>Generate HMAC invite token with the real session ID; store BCrypt hash.</li>
 *   <li>Dispatch invite email with the token URL.</li>
 *   <li>Create a stub EvaluationReport (PENDING) to track AI pipeline state.</li>
 * </ol>
 *
 * <h2>In-browser interview flow (PRD v3)</h2>
 * <ol>
 *   <li>{@link #initInterview()} — candidate opens the room; returns questions + pre-signed recording URL.</li>
 *   <li>{@link #startInterview()} — browser calls when candidate confirms camera/mic; INVITED → IN_PROGRESS.</li>
 *   <li>{@link #completeInterview(CompleteInterviewRequest)} — browser calls after all questions; merges transcripts
 *       into {@code questionsJson}, flips to COMPLETED, settles billing, triggers evaluation.</li>
 *   <li>{@link #failInterview(String)} — browser calls on fatal error; sets ERROR state.</li>
 * </ol>
 *
 * <h2>Session cost</h2>
 * <p>Configurable via {@code app.billing.session-cost-paise} (default 5000 = ₹50).
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final Duration RECORDING_URL_EXPIRY = Duration.ofHours(2);

    private final InterviewSessionRepository sessionRepository;
    private final EvaluationReportRepository evaluationReportRepository;
    private final JobOpeningRepository       jobOpeningRepository;
    private final CandidateRepository        candidateRepository;
    private final CompanyRepository          companyRepository;
    private final WalletService              walletService;
    private final TokenService               tokenService;
    private final EmailService               emailService;
    private final SecurityProperties         securityProperties;
    private final StorageService             storageService;
    private final ObjectMapper               objectMapper;

    @Value("${app.billing.session-cost-paise:5000}")
    private long sessionCostPaise;

    @Value("${app.frontend.base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    public SessionService(InterviewSessionRepository sessionRepository,
                          EvaluationReportRepository evaluationReportRepository,
                          JobOpeningRepository jobOpeningRepository,
                          CandidateRepository candidateRepository,
                          CompanyRepository companyRepository,
                          WalletService walletService,
                          TokenService tokenService,
                          EmailService emailService,
                          SecurityProperties securityProperties,
                          StorageService storageService,
                          ObjectMapper objectMapper) {
        this.sessionRepository          = sessionRepository;
        this.evaluationReportRepository = evaluationReportRepository;
        this.jobOpeningRepository       = jobOpeningRepository;
        this.candidateRepository        = candidateRepository;
        this.companyRepository          = companyRepository;
        this.walletService              = walletService;
        this.tokenService               = tokenService;
        this.emailService               = emailService;
        this.securityProperties         = securityProperties;
        this.storageService             = storageService;
        this.objectMapper               = objectMapper;
    }

    // =========================================================================
    // Employer-facing operations
    // =========================================================================

    /**
     * Creates an interview session, reserves billing funds, and dispatches the
     * invite email. The session starts in INVITED state.
     */
    @Auditable(action = "SESSION_CREATED", entityType = "SESSION")
    @Transactional
    public SessionResponse create(CreateSessionRequest req) {
        UUID companyId = SecurityContext.requireCompanyId();

        JobOpening job = jobOpeningRepository.findByCompanyIdAndId(companyId, req.jobOpeningId())
                .orElseThrow(() -> new ResourceNotFoundException("JobOpening", req.jobOpeningId()));

        if (job.getJdExtractionStatus() != PipelineStatus.DONE) {
            throw new ValidationException(
                    "Job description must be fully extracted before creating a session. " +
                    "Current status: " + job.getJdExtractionStatus());
        }

        Candidate candidate = candidateRepository.findByCompanyIdAndId(companyId, req.candidateId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate", req.candidateId()));

        if (!candidate.getJobOpeningId().equals(req.jobOpeningId())) {
            throw new ValidationException("Candidate does not belong to the specified job opening.");
        }

        // Persist session first to obtain the real session ID
        InterviewSession session = new InterviewSession();
        session.setCompanyId(companyId);
        session.setJobOpeningId(req.jobOpeningId());
        session.setCandidateId(req.candidateId());
        session.setStatus(SessionStatus.INVITED);
        session.setQuestionGenerationStatus(PipelineStatus.PENDING);
        session.setScheduledStartAt(req.scheduledAt());
        session.setInviteExpiresAt(
                OffsetDateTime.now(ZoneOffset.UTC)
                        .plus(securityProperties.getInvite().getExpiration()));
        // Temporary unique hash — replaced after we have the session ID
        session.setInviteTokenHash("init-" + UUID.randomUUID());
        session = sessionRepository.save(session);

        // Reserve billing funds with the real session ID
        walletService.reserveFunds(companyId, session.getId(), sessionCostPaise);

        // Generate invite token with the real session ID, store hash
        String inviteToken = tokenService.generateInviteToken(
                session.getId(), candidate.getId(), companyId);
        session.setInviteTokenHash(tokenService.hashToken(inviteToken));
        session = sessionRepository.save(session);

        // Create stub evaluation report to track AI pipeline state
        EvaluationReport report = new EvaluationReport();
        report.setCompanyId(companyId);
        report.setSessionId(session.getId());
        report.setGenerationStatus(PipelineStatus.PENDING);
        evaluationReportRepository.save(report);

        // Dispatch invite email
        String companyName = companyRepository.findById(companyId)
                .map(c -> c.getName())
                .orElse("InterviewIQ");
        String inviteUrl = frontendBaseUrl + "/interview?token=" + inviteToken;
        emailService.sendCandidateInviteEmail(
                candidate.getEmail(), candidate.getFullName(), companyName, inviteUrl, companyId);

        log.info("Session created: sessionId={} companyId={} candidateId={}",
                session.getId(), companyId, candidate.getId());

        return SessionResponse.from(session);
    }

    @Transactional(readOnly = true)
    public Page<SessionResponse> listByJob(UUID jobOpeningId, Pageable pageable) {
        UUID companyId = SecurityContext.requireCompanyId();
        return sessionRepository
                .findAllByCompanyIdAndJobOpeningIdOrderByCreatedAtDesc(companyId, jobOpeningId, pageable)
                .map(SessionResponse::from);
    }

    /**
     * Lists all sessions for the caller's company, optionally filtered by status.
     * Used by the sessions overview page and the dashboard.
     */
    @Transactional(readOnly = true)
    public Page<SessionResponse> listAll(SessionStatus status, Pageable pageable) {
        UUID companyId = SecurityContext.requireCompanyId();
        if (status != null) {
            return sessionRepository
                    .findAllByCompanyIdAndStatusOrderByCreatedAtDesc(companyId, status, pageable)
                    .map(SessionResponse::from);
        }
        return sessionRepository
                .findAllByCompanyIdOrderByCreatedAtDesc(companyId, pageable)
                .map(SessionResponse::from);
    }

    @Transactional(readOnly = true)
    public SessionResponse get(UUID sessionId) {
        return SessionResponse.from(requireSession(sessionId));
    }

    /**
     * Cancels a session that is still in INVITED state.
     * Releases the billing reservation.
     */
    @Auditable(action = "SESSION_CANCELLED", entityType = "SESSION", entityIdArg = 0)
    @Transactional
    public SessionResponse cancel(UUID sessionId) {
        InterviewSession session = requireSession(sessionId);

        if (session.getStatus() != SessionStatus.INVITED) {
            throw new SessionStateException(
                    "Cannot cancel a session in state " + session.getStatus() +
                    ". Only INVITED sessions can be cancelled.");
        }

        session.setStatus(SessionStatus.CANCELLED);
        session.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC));
        sessionRepository.save(session);
        walletService.releaseFunds(session.getCompanyId(), sessionId);

        log.info("Session cancelled: sessionId={}", sessionId);
        return SessionResponse.from(session);
    }

    @Transactional(readOnly = true)
    public EvaluationReportResponse getEvaluation(UUID sessionId) {
        UUID companyId = SecurityContext.requireCompanyId();
        EvaluationReport report = evaluationReportRepository
                .findByCompanyIdAndSessionId(companyId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("EvaluationReport for session", sessionId));
        return EvaluationReportResponse.from(report);
    }

    /**
     * Returns the session for the authenticated candidate (lightweight — no questions JSON).
     */
    @Transactional(readOnly = true)
    public SessionResponse getCandidateSession() {
        UUID sessionId = SecurityContext.requireCandidate().sessionId();
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
        return SessionResponse.from(session);
    }

    @Transactional(readOnly = true)
    public InterviewInitResponse initInterview() {
        CandidatePrincipal principal = SecurityContext.requireCandidate();
        UUID sessionId  = principal.sessionId();
        UUID candidateId = principal.candidateId();

        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));

        String recordingKey = "recordings/" + session.getCompanyId() + "/" + sessionId + ".webm";
        String uploadUrl = storageService.generatePresignedUploadUrl(
                recordingKey, "video/webm", RECORDING_URL_EXPIRY);

        boolean googleVerified = candidateRepository.findById(candidateId)
                .map(Candidate::isGoogleVerified)
                .orElse(false);

        return new InterviewInitResponse(
                session.getId(),
                session.getStatus(),
                session.getQuestionGenerationStatus(),
                session.getQuestionsJson(),
                uploadUrl,
                recordingKey,
                session.getScheduledStartAt(),
                session.getInviteExpiresAt(),
                googleVerified
        );
    }

    /**
     * Transitions the session from INVITED → IN_PROGRESS when the candidate confirms
     * their camera/microphone are working and begins the interview.
     *
     * <p>Idempotent: calling this on an already-IN_PROGRESS session is a no-op that
     * returns the current session state. This handles browser refresh mid-interview.
     */
    @Transactional
    public SessionResponse startInterview() {
        UUID sessionId = SecurityContext.requireCandidate().sessionId();
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));

        if (session.getStatus() == SessionStatus.INVITED) {
            session.setStatus(SessionStatus.IN_PROGRESS);
            session.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
            sessionRepository.save(session);
            log.info("Interview started: sessionId={}", sessionId);
        } else if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new SessionStateException(
                    "Cannot start interview in current state: " + session.getStatus());
        }

        return SessionResponse.from(session);
    }

    @Transactional
    public SessionResponse completeInterview(CompleteInterviewRequest req) {
        UUID sessionId = SecurityContext.requireCandidate().sessionId();
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));

        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new SessionStateException(
                    "Cannot complete interview in state " + session.getStatus() +
                    ". Session must be IN_PROGRESS.");
        }

        String mergedJson = mergeAnswersIntoQuestionsJson(
                session.getQuestionsJson(), req.answers());
        session.setQuestionsJson(mergedJson);

        if (req.proctoringFlags() != null && !req.proctoringFlags().isEmpty()) {
            try {
                session.setProctoringFlagsJsonb(objectMapper.writeValueAsString(req.proctoringFlags()));
            } catch (JsonProcessingException e) {
                log.warn("Could not serialize proctoring flags for sessionId={}: {}", sessionId, e.getMessage());
            }
        }

        if (req.recordingS3Key() != null && !req.recordingS3Key().isBlank()) {
            session.setRecordingS3Key(req.recordingS3Key());
        }

        if (session.getStartedAt() != null) {
            long durationSecs = java.time.Duration.between(
                    session.getStartedAt(), OffsetDateTime.now(ZoneOffset.UTC)).toSeconds();
            session.setDurationSeconds((int) Math.max(0, durationSecs));
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        session.setStatus(SessionStatus.COMPLETED);
        session.setEndedAt(now);
        sessionRepository.save(session);

        walletService.settleFunds(session.getCompanyId(), sessionId, sessionCostPaise);

        evaluationReportRepository
                .findByCompanyIdAndSessionId(session.getCompanyId(), sessionId)
                .ifPresent(report -> {
                    report.setGenerationStatus(PipelineStatus.PENDING);
                    evaluationReportRepository.save(report);
                });

        log.info("Interview completed: sessionId={} durationSeconds={}", sessionId, session.getDurationSeconds());
        return SessionResponse.from(session);
    }

    @Transactional
    public SessionResponse failInterview(String errorReason) {
        UUID sessionId = SecurityContext.requireCandidate().sessionId();
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));

        if (session.getStatus() == SessionStatus.COMPLETED
                || session.getStatus() == SessionStatus.CANCELLED
                || session.getStatus() == SessionStatus.ERROR) {
            return SessionResponse.from(session);
        }

        session.setStatus(SessionStatus.ERROR);
        session.setErrorCode("BROWSER_ERROR");
        session.setErrorMessage(errorReason != null ? errorReason : "Unknown browser error");
        session.setEndedAt(OffsetDateTime.now(ZoneOffset.UTC));
        sessionRepository.save(session);

        walletService.releaseFunds(session.getCompanyId(), sessionId);
        log.warn("Interview failed: sessionId={} reason={}", sessionId, errorReason);
        return SessionResponse.from(session);
    }

    // =========================================================================
    // Internal helpers (used by WebhookService)
    // =========================================================================

    public InterviewSession requireSessionById(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
    }

    public void save(InterviewSession session) {
        sessionRepository.save(session);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private InterviewSession requireSession(UUID sessionId) {
        UUID companyId = SecurityContext.requireCompanyId();
        return sessionRepository.findByCompanyIdAndId(companyId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
    }

    private String mergeAnswersIntoQuestionsJson(
            String questionsJson,
            List<CompleteInterviewRequest.QuestionAnswer> answers) {

        if (questionsJson == null || questionsJson.isBlank() || answers == null || answers.isEmpty()) {
            return questionsJson;
        }

        try {
            Map<Integer, String> answerByOrder = answers.stream()
                    .collect(Collectors.toMap(
                            CompleteInterviewRequest.QuestionAnswer::questionOrder,
                            a -> a.transcript() != null ? a.transcript() : "",
                            (a, b) -> a));

            ArrayNode questions = (ArrayNode) objectMapper.readTree(questionsJson);
            for (int i = 0; i < questions.size(); i++) {
                ObjectNode q = (ObjectNode) questions.get(i);
                int order = q.path("order").asInt(i + 1);
                String transcript = answerByOrder.get(order);
                if (transcript != null) {
                    q.put("answer", transcript);
                }
            }
            return objectMapper.writeValueAsString(questions);

        } catch (JsonProcessingException e) {
            log.error("Failed to merge answers into questionsJson: {}", e.getMessage());
            return questionsJson;
        }
    }
}
