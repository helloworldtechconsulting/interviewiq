package com.interviewiq.session.service;

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
import com.interviewiq.session.dto.CreateSessionRequest;
import com.interviewiq.session.dto.EvaluationReportResponse;
import com.interviewiq.session.dto.SessionResponse;
import com.interviewiq.session.infrastructure.EvaluationReportRepository;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.audit.annotation.Auditable;
import com.interviewiq.auth.config.SecurityProperties;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.SessionStateException;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.shared.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

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
 * <h2>Session cost</h2>
 * <p>Configurable via {@code app.billing.session-cost-paise} (default 5000 = ₹50).
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final InterviewSessionRepository sessionRepository;
    private final EvaluationReportRepository evaluationReportRepository;
    private final JobOpeningRepository       jobOpeningRepository;
    private final CandidateRepository        candidateRepository;
    private final CompanyRepository          companyRepository;
    private final WalletService              walletService;
    private final TokenService               tokenService;
    private final EmailService               emailService;
    private final SecurityProperties         securityProperties;

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
                          SecurityProperties securityProperties) {
        this.sessionRepository          = sessionRepository;
        this.evaluationReportRepository = evaluationReportRepository;
        this.jobOpeningRepository       = jobOpeningRepository;
        this.candidateRepository        = candidateRepository;
        this.companyRepository          = companyRepository;
        this.walletService              = walletService;
        this.tokenService               = tokenService;
        this.emailService               = emailService;
        this.securityProperties         = securityProperties;
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
        session.setScheduledAt(req.scheduledAt());
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

    /**
     * Employer endpoint: sets the Google Meet URL for a session owned by the caller's company.
     * Allowed while the session is INVITED or STARTED.
     */
    @Transactional
    public SessionResponse setMeetUrl(UUID sessionId, String meetUrl) {
        InterviewSession session = requireSession(sessionId);

        if (session.getStatus() != SessionStatus.INVITED && session.getStatus() != SessionStatus.STARTED) {
            throw new SessionStateException(
                    "Cannot update meet URL for session in state " + session.getStatus());
        }
        if (meetUrl == null || meetUrl.isBlank()) {
            throw new ValidationException("Google Meet URL must not be blank.");
        }

        session.setGoogleMeetUrl(meetUrl);
        sessionRepository.save(session);
        log.info("Meet URL set by employer: sessionId={}", sessionId);
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

    // =========================================================================
    // Candidate-facing operations (called from candidate auth chain)
    // =========================================================================

    /**
     * Returns the session for the authenticated candidate.
     */
    @Transactional(readOnly = true)
    public SessionResponse getCandidateSession() {
        UUID sessionId = SecurityContext.requireCandidate().sessionId();
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
        return SessionResponse.from(session);
    }

    /**
     * Records the Google Meet URL for the session. Called by the candidate
     * after they accept the invite and provide the meeting link.
     */
    @Transactional
    public SessionResponse setCandidateMeetUrl(String googleMeetUrl) {
        UUID sessionId = SecurityContext.requireCandidate().sessionId();
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));

        if (session.getStatus() != SessionStatus.INVITED) {
            throw new SessionStateException(
                    "Cannot update meet URL for session in state " + session.getStatus());
        }
        if (googleMeetUrl == null || googleMeetUrl.isBlank()) {
            throw new ValidationException("Google Meet URL must not be blank.");
        }

        session.setGoogleMeetUrl(googleMeetUrl);
        sessionRepository.save(session);
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
}
