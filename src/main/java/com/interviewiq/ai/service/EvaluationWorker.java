package com.interviewiq.ai.service;

import com.interviewiq.session.domain.EvaluationReport;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.EvaluationReportRepository;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.session.service.SessionCompletionService;
import com.interviewiq.ai.config.AiWorkflowProperties;
import com.interviewiq.shared.config.WorkerProperties;
import com.interviewiq.shared.domain.PipelineStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Claims and runs pending evaluations (PRD v2.1 §7.5.5, §7.9).
 *
 * <p>This class is responsible for <em>claiming and retrying</em>;
 * {@link EvaluationService} is responsible for producing a report. Keeping them
 * apart matters because a report that fails the evidence requirement is a
 * retryable outcome, and mixing that with attempt bookkeeping is how attempt
 * counters go wrong.
 *
 * <h2>Timing</h2>
 *
 * <p>The 30-second poll is a crash-recovery safety net, not the primary trigger.
 * Completion queues the report immediately (§7.5.5), so a recruiter watching a
 * candidate finish does not wait for a tick. The hard SLA is 30 minutes and the
 * soft target ~5, and the PRD is pointed that the longer SLA is "a promise we can
 * always keep, not a delay we deliberately introduce".
 *
 * <h2>Settlement</h2>
 *
 * <p>₹100 settles only when the report is ready, via
 * {@link SessionCompletionService#markReportReady}. A session whose evaluation
 * permanently fails reaches {@code ERROR} and is never charged — an employer pays
 * for a readable report, not for an interview that failed to score.
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationWorker {

    private static final Logger log = LoggerFactory.getLogger(EvaluationWorker.class);

    private final EvaluationReportRepository reportRepository;
    private final InterviewSessionRepository sessionRepository;
    private final EvaluationService evaluationService;
    private final SessionCompletionService completionService;
    private final WorkerProperties workerProperties;
    private final AiWorkflowProperties aiProperties;

    /** Self-reference so {@code @Transactional} advice applies to per-report calls. */
    @Lazy
    @Autowired
    private EvaluationWorker self;

    public EvaluationWorker(EvaluationReportRepository reportRepository,
                            InterviewSessionRepository sessionRepository,
                            EvaluationService evaluationService,
                            SessionCompletionService completionService,
                            WorkerProperties workerProperties,
                            AiWorkflowProperties aiProperties) {
        this.reportRepository  = reportRepository;
        this.sessionRepository = sessionRepository;
        this.evaluationService = evaluationService;
        this.completionService = completionService;
        this.workerProperties  = workerProperties;
        this.aiProperties      = aiProperties;
    }

    /**
     * Claims a bounded batch and evaluates each report.
     *
     * <p>The claim is a single atomic statement using {@code FOR UPDATE SKIP
     * LOCKED} with an explicit {@code LIMIT}, which increments the attempt
     * counter under the same row lock (§7.9). Rows come back already marked
     * IN_PROGRESS, so this scheduler never sweeps IN_PROGRESS rows for recovery —
     * staleness is handled inside the claim.
     */
    @Scheduled(initialDelayString = "PT25S", fixedDelayString = "PT30S")
    public void evaluatePendingReports() {
        OffsetDateTime staleBefore =
                OffsetDateTime.now(ZoneOffset.UTC).minus(workerProperties.getStaleClaimAfter());

        List<EvaluationReport> claimed = reportRepository.claimBatch(
                workerProperties.getEvaluationBatchSize(), staleBefore);

        if (claimed.isEmpty()) {
            return;
        }
        log.debug("EvaluationWorker: claimed {} evaluation(s)", claimed.size());

        for (EvaluationReport report : claimed) {
            self.evaluateSingle(report);  // through the proxy so @Transactional applies
        }
    }

    /**
     * Evaluates one already-claimed report.
     *
     * <p>The attempt counter was incremented by the claim, under the row lock, so
     * this must not increment it again — doing so would retire reports at half
     * the configured limit.
     */
    @Transactional
    public void evaluateSingle(EvaluationReport report) {
        int attempt = report.getGenerationAttempts();
        int maxAttempts = aiProperties.getEvaluationMaxAttempts();

        if (attempt > maxAttempts) {
            failPermanently(report, "Evaluation did not succeed after " + maxAttempts + " attempts.");
            return;
        }

        InterviewSession session = sessionRepository.findById(report.getSessionId()).orElse(null);
        if (session == null) {
            failPermanently(report, "The session for this report no longer exists.");
            return;
        }

        try {
            evaluationService.evaluate(session, report);

            report.setGeneratedAt(OffsetDateTime.now(ZoneOffset.UTC));
            report.setGenerationStatus(PipelineStatus.DONE);
            reportRepository.save(report);

            // Only now does the session reach COMPLETED and the Rs.100 settle.
            completionService.markReportReady(session.getId());

            log.info("Evaluation complete: sessionId={} reportId={} attempt={} score={}",
                    session.getId(), report.getId(), attempt, report.getOverallScore());

        } catch (Exception e) {
            if (attempt >= maxAttempts) {
                failPermanently(report, e.getMessage());
                return;
            }
            // Back to PENDING so the next claim retries. The claim increments the
            // attempt counter again, which is what bounds this loop.
            log.warn("Evaluation attempt {}/{} failed for reportId={}: {}",
                    attempt, maxAttempts, report.getId(), e.getMessage());
            report.setGenerationStatus(PipelineStatus.PENDING);
            reportRepository.save(report);
        }
    }

    /**
     * Retires a report and flags its session for manual review.
     *
     * <p>§7.5.5: "on persistent failure the session is flagged ERROR for manual
     * review and the employer is notified." The session never reaches COMPLETED,
     * so the ₹100 reservation is never settled — the employer is not charged for
     * a report they cannot read.
     */
    private void failPermanently(EvaluationReport report, String reason) {
        report.setGenerationStatus(PipelineStatus.FAILED);
        reportRepository.save(report);

        sessionRepository.findById(report.getSessionId()).ifPresent(session -> {
            if (session.getStatus() == SessionStatus.EVALUATING) {
                session.setStatus(SessionStatus.ERROR);
                session.setErrorCode("EVALUATION_FAILED");
                session.setErrorMessage(reason);
                sessionRepository.save(session);
            }
        });

        log.error("Evaluation permanently failed: reportId={} sessionId={} reason={}",
                report.getId(), report.getSessionId(), reason);
    }
}
