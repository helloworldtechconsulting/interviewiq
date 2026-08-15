package com.interviewiq.session.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.EvaluationReportRepository;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.session.infrastructure.SessionAnswerRepository;
import com.interviewiq.shared.config.BillingProperties;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Moves a finished interview into evaluation (PRD v2.1 §7.4.4, §7.5.5).
 *
 * <p>Kept separate from {@link InterviewRoomService} because completion is reached
 * from three different places — the candidate ending the interview, the hard timer
 * firing, and a critical proctoring termination — and all three must do exactly
 * the same thing.
 *
 * <h2>Completion triggers evaluation immediately</h2>
 *
 * <p>§7.5.5: "Completion triggers an evaluation event immediately. The polling
 * worker remains only as a crash-recovery safety net — the report must not wait
 * for the next poll tick." The hard SLA is 30 minutes but the soft target is ~5,
 * and the PRD is pointed about not treating the longer SLA as licence to queue:
 * thirty minutes is fine for a batch reviewed next morning, and not fine for a
 * recruiter who just watched a candidate finish.
 *
 * <p>Note the session does <strong>not</strong> settle its ₹100 here. Settlement
 * happens when the session reaches {@code COMPLETED} — that is, when the report is
 * ready — so a candidate whose evaluation permanently fails is never charged for a
 * report the employer cannot read.
 */
@Service
public class SessionCompletionService {

    private static final Logger log = LoggerFactory.getLogger(SessionCompletionService.class);

    /** Above this fraction answered, a partial evaluation is generated (§7.5.7). */
    private static final double PARTIAL_EVALUATION_THRESHOLD = 0.5;

    private final InterviewSessionRepository sessionRepository;
    private final SessionAnswerRepository answerRepository;
    private final EvaluationReportRepository reportRepository;
    private final WalletService walletService;
    private final BillingProperties billingProperties;
    private final ObjectMapper objectMapper;

    public SessionCompletionService(InterviewSessionRepository sessionRepository,
                                    SessionAnswerRepository answerRepository,
                                    EvaluationReportRepository reportRepository,
                                    WalletService walletService,
                                    BillingProperties billingProperties,
                                    ObjectMapper objectMapper) {
        this.sessionRepository  = sessionRepository;
        this.answerRepository   = answerRepository;
        this.reportRepository   = reportRepository;
        this.walletService      = walletService;
        this.billingProperties  = billingProperties;
        this.objectMapper       = objectMapper;
    }

    /**
     * Transitions a live interview to {@code EVALUATING} and queues its report.
     *
     * <p>Idempotent under a row lock: the candidate's {@code session.end} and the
     * hard timer can race, and both reaching here must produce one evaluation, not
     * two.
     *
     * @param timedOut whether the hard timer ended it rather than the candidate
     */
    @Transactional
    public void completeInterview(UUID sessionId, boolean timedOut) {
        InterviewSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));

        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            log.debug("Interview already left IN_PROGRESS, skipping completion: sessionId={} status={}",
                    sessionId, session.getStatus());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        session.setStatus(SessionStatus.EVALUATING);
        session.setEndedAt(now);
        if (session.getStartedAt() != null) {
            session.setDurationSeconds((int)
                    java.time.Duration.between(session.getStartedAt(), now).toSeconds());
        }
        sessionRepository.save(session);

        int totalQuestions = countQuestions(session);
        long answered = answerRepository.countAnsweredBySessionId(sessionId);
        boolean partial = totalQuestions > 0 && answered < totalQuestions;

        if (totalQuestions > 0 && (double) answered / totalQuestions < PARTIAL_EVALUATION_THRESHOLD) {
            // Below half, there is not enough signal to produce a report a
            // recruiter should act on (§7.5.7). The session is flagged rather
            // than scored, and — because it never reaches COMPLETED — never
            // settles its Rs.100.
            session.setStatus(SessionStatus.ERROR);
            session.setErrorCode("INSUFFICIENT_ANSWERS");
            session.setErrorMessage("Only " + answered + " of " + totalQuestions
                    + " questions were answered; too little to evaluate.");
            sessionRepository.save(session);
            log.warn("Interview ended with too few answers to evaluate: sessionId={} answered={}/{}",
                    sessionId, answered, totalQuestions);
            return;
        }

        queueEvaluation(session, partial);

        log.info("Interview completed: sessionId={} answered={}/{} timedOut={} partial={}",
                sessionId, answered, totalQuestions, timedOut, partial);
    }

    /**
     * Creates or resets the report row so a worker claims it on the next poll —
     * and, because the row is written as PENDING inside this transaction, the
     * KEDA queue-depth scaler sees the work immediately too.
     */
    private void queueEvaluation(InterviewSession session, boolean partial) {
        EvaluationReport report = reportRepository.findBySessionId(session.getId())
                .orElseGet(EvaluationReport::new);

        report.setCompanyId(session.getCompanyId());
        report.setSessionId(session.getId());
        report.setGenerationStatus(PipelineStatus.PENDING);
        report.setPartial(partial);
        reportRepository.save(report);
    }

    private int countQuestions(InterviewSession session) {
        String json = session.getQuestionsJson();
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            return array.isArray() ? array.size() : 0;
        } catch (Exception e) {
            log.error("Unparseable question bank on session {}", session.getId(), e);
            return 0;
        }
    }

    /**
     * Marks a session {@code COMPLETED} once its report is ready, and settles the
     * ₹100.
     *
     * <p>Called by the evaluation worker on success. Settlement is deliberately
     * here rather than at interview end: an employer pays for a readable report,
     * not for an interview that failed to score.
     */
    @Transactional
    public void markReportReady(UUID sessionId) {
        InterviewSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));

        if (session.getStatus() != SessionStatus.EVALUATING) {
            log.debug("Session not awaiting a report, skipping: sessionId={} status={}",
                    sessionId, session.getStatus());
            return;
        }

        session.setStatus(SessionStatus.COMPLETED);
        sessionRepository.save(session);

        // Idempotent per session inside WalletService, so a retried completion
        // cannot double-charge (§7.8.1).
        walletService.settleFunds(session.getCompanyId(), sessionId,
                billingProperties.getSessionCostPaise());

        log.info("Report ready and session settled: sessionId={}", sessionId);
    }
}
