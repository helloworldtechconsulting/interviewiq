package com.interviewengine.ai.service;

import com.interviewengine.job.domain.EmployerQuestion;
import com.interviewengine.job.domain.JobOpening;
import com.interviewengine.job.infrastructure.EmployerQuestionRepository;
import com.interviewengine.job.infrastructure.JobOpeningRepository;
import com.interviewengine.shared.config.WorkerProperties;
import com.interviewengine.shared.domain.PipelineStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Generates the question bank for openings whose JD has finished extracting
 * (INTIQ-17, stage 1).
 *
 * <p>Runs on the same claim-then-work discipline as the other AI workers: the
 * claim marks {@code IN_PROGRESS} under {@code FOR UPDATE SKIP LOCKED} and
 * commits, then the LLM call happens outside any transaction, then the result is
 * written in a short second transaction. Holding a database connection across a
 * ~20-second model call is the pattern INTIQ-25 existed to remove, and adding a
 * new worker that reintroduced it would undo that.
 *
 * <p><strong>Failure is terminal after {@code maxAttempts}.</strong> The claim
 * increments the attempt counter and the query filters on it, so a bank that
 * cannot be generated stops being retried rather than looping forever. The
 * opening then cannot invite candidates — which is the correct outcome, because
 * an interview with no questions is worse than a job that visibly needs
 * attention.
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class QuestionBankWorker {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankWorker.class);

    /** Three attempts, then the opening is left FAILED for a human to look at. */
    private static final int MAX_ATTEMPTS = 3;

    private final JobOpeningRepository jobOpeningRepository;
    private final EmployerQuestionRepository employerQuestionRepository;
    private final QuestionBankService bankService;
    private final WorkerProperties workerProperties;

    /** Self-injection so the transactional boundaries below actually apply. */
    @Autowired
    @Lazy
    private QuestionBankWorker self;

    public QuestionBankWorker(JobOpeningRepository jobOpeningRepository,
                              EmployerQuestionRepository employerQuestionRepository,
                              QuestionBankService bankService,
                              WorkerProperties workerProperties) {
        this.jobOpeningRepository       = jobOpeningRepository;
        this.employerQuestionRepository = employerQuestionRepository;
        this.bankService                = bankService;
        this.workerProperties           = workerProperties;
    }

    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "PT30S")
    public void generatePendingBanks() {
        OffsetDateTime staleBefore =
                OffsetDateTime.now(ZoneOffset.UTC).minus(workerProperties.getStaleClaimAfter());

        List<JobOpening> claimed = self.claim(staleBefore);
        if (claimed.isEmpty()) {
            return;
        }

        log.debug("QuestionBankWorker: claimed {} opening(s)", claimed.size());
        for (JobOpening job : claimed) {
            generateFor(job);
        }
    }

    /** The claim, in its own committed transaction. */
    @Transactional
    public List<JobOpening> claim(OffsetDateTime staleBefore) {
        return jobOpeningRepository.claimForQuestionBank(
                workerProperties.getQuestionGenerationBatchSize(), staleBefore, MAX_ATTEMPTS);
    }

    /**
     * Generates one bank. The model call happens here, outside a transaction.
     */
    private void generateFor(JobOpening job) {
        try {
            List<String> employerQuestions = employerQuestionRepository
                    .findAllByJobOpeningIdOrderByDisplayOrderAscCreatedAtAsc(job.getId())
                    .stream()
                    .filter(EmployerQuestion::isUsable)
                    .map(EmployerQuestion::getQuestionText)
                    .toList();

            String bank = bankService.generate(job, employerQuestions);
            self.recordSuccess(job.getId(), bank);

            log.info("QuestionBankWorker: generated bank for jobId={}", job.getId());

        } catch (Exception e) {
            log.error("QuestionBankWorker: bank generation failed for jobId={} attempt={}",
                    job.getId(), job.getQuestionBankAttempts(), e);
            self.recordFailure(job.getId());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(java.util.UUID jobId, String bankJson) {
        jobOpeningRepository.findById(jobId).ifPresent(job -> {
            job.setQuestionBankJsonb(bankJson);
            job.setQuestionBankStatus(PipelineStatus.DONE);
            job.setQuestionBankGeneratedAt(OffsetDateTime.now(ZoneOffset.UTC));
            jobOpeningRepository.save(job);
        });
    }

    /**
     * Marks the attempt failed in its own transaction.
     *
     * <p>{@code REQUIRES_NEW} for the reason INTIQ-81 documented: writing a
     * failure marker inside a transaction that is already doomed means the marker
     * never commits, so the row is re-claimed forever and the attempt counter
     * never advances.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(java.util.UUID jobId) {
        jobOpeningRepository.findById(jobId).ifPresent(job -> {
            if (job.getQuestionBankAttempts() >= MAX_ATTEMPTS) {
                job.setQuestionBankStatus(PipelineStatus.FAILED);
                log.error("QuestionBankWorker: jobId={} exhausted {} attempts; this opening cannot "
                        + "invite candidates until the bank is regenerated", jobId, MAX_ATTEMPTS);
            } else {
                // Back to PENDING so the next pass picks it up; the attempt
                // counter incremented on claim is what eventually stops it.
                job.setQuestionBankStatus(PipelineStatus.PENDING);
            }
            jobOpeningRepository.save(job);
        });
    }
}
