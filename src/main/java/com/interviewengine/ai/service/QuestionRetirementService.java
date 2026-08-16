package com.interviewengine.ai.service;

import com.interviewengine.ai.domain.QuestionTelemetry;
import com.interviewengine.ai.domain.RetirementReason;
import com.interviewengine.ai.infrastructure.QuestionTelemetryRepository;
import com.interviewengine.session.infrastructure.InterviewSessionRepository;
import com.interviewengine.shared.config.QuestionQualityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Auto-retirement — the feedback loop that replaces recruiter review
 * (PRD v2.1 §7.5, INTIQ-93 item 4).
 *
 * <h2>Why thresholds are only applied after a minimum sample</h2>
 *
 * <p>Every rule here is a judgement about a distribution, and a distribution of
 * three is not one. A question asked twice, skipped once, has a 50% skip rate
 * and means nothing. Retiring on it would remove good questions early in an
 * opening's life — exactly when the bank is smallest and can least afford to
 * lose them — and it would do so invisibly.
 *
 * <p>{@code minimumSample} is therefore a gate on judging at all, not a
 * tie-break. Below it the question is left alone regardless of how bad it looks.
 *
 * <h2>Order of checks</h2>
 *
 * <p>Checked most-diagnostic first, because the reason is recorded and only the
 * first one sticks. A question that is both skipped constantly and produces no
 * variance should be recorded as high-skip: that is the fact a human can act on,
 * whereas "no variance" on a question nobody answers is arithmetic rather than
 * insight.
 */
@Service
public class QuestionRetirementService {

    private static final Logger log = LoggerFactory.getLogger(QuestionRetirementService.class);

    private final QuestionTelemetryRepository repository;
    private final QuestionQualityProperties properties;
    private final InterviewSessionRepository sessionRepository;

    public QuestionRetirementService(QuestionTelemetryRepository repository,
                                     QuestionQualityProperties properties,
                                     InterviewSessionRepository sessionRepository) {
        this.repository        = repository;
        this.properties        = properties;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Folds one answered question into its telemetry row, creating the row on
     * first sight.
     *
     * <p>Called from the interview room as answers land. Failures are absorbed by
     * the caller: telemetry is diagnostic, and losing a data point must never
     * cost a candidate their answer.
     */
    @Transactional
    public void recordAnswer(UUID jobOpeningId,
                             String bankQuestionId,
                             String questionText,
                             boolean skipped,
                             String transcript,
                             Short score) {
        if (bankQuestionId == null || bankQuestionId.isBlank()) {
            // Employer questions and follow-ups have no bank id. Follow-ups are
            // generated live and never reused, and employer questions are the
            // employer's to judge, not ours to retire.
            return;
        }

        QuestionTelemetry row = repository
                .findByJobOpeningIdAndBankQuestionId(jobOpeningId, bankQuestionId)
                .orElseGet(() -> {
                    QuestionTelemetry fresh = new QuestionTelemetry();
                    fresh.setJobOpeningId(jobOpeningId);
                    fresh.setBankQuestionId(bankQuestionId);
                    fresh.setQuestionText(questionText);
                    return fresh;
                });

        row.recordAnswer(skipped, wordCount(transcript), score);
        repository.save(row);
    }

    /**
     * Folds a per-question score into the running variance, after evaluation.
     *
     * <p>Separate from {@link #recordAnswer} because the two facts arrive at
     * different times from different services: the ask and the skip are known in
     * the interview room, the score only exists once the evaluation has run. The
     * row is created by the first call, so this one only ever updates.
     *
     * <p>Resolves the opening from the session rather than taking it as an
     * argument, so the caller — which is working with answers, not jobs — does
     * not have to carry it.
     */
    @Transactional
    public void recordScore(UUID sessionId, String bankQuestionId, short score) {
        UUID jobOpeningId = sessionRepository.findById(sessionId)
                .map(s -> s.getJobOpeningId())
                .orElse(null);
        if (jobOpeningId == null) {
            return;
        }

        repository.findByJobOpeningIdAndBankQuestionId(jobOpeningId, bankQuestionId)
                .ifPresent(row -> {
                    row.recordScore(score);
                    repository.save(row);
                });
    }

    /** Records a candidate's one-click flag against a question. */
    @Transactional
    public void recordCandidateFlag(UUID jobOpeningId, String bankQuestionId) {
        repository.findByJobOpeningIdAndBankQuestionId(jobOpeningId, bankQuestionId)
                .ifPresent(row -> {
                    row.recordCandidateFlag();
                    repository.save(row);
                });
    }

    /**
     * Evaluates every live question with enough data and retires those that
     * breach a threshold.
     *
     * @return how many were retired
     */
    @Transactional
    public int evaluateAndRetire() {
        List<QuestionTelemetry> live =
                repository.findLiveWithMinimumSample(properties.getMinimumSample());

        int retired = 0;
        for (QuestionTelemetry row : live) {
            Optional<RetirementReason> reason = judge(row);
            if (reason.isPresent()) {
                row.retire(reason.get());
                repository.save(row);
                retired++;
                log.info("Question auto-retired: jobId={} questionId={} reason={} "
                                + "asked={} skipRate={} shortRate={} variance={} flags={}",
                        row.getJobOpeningId(), row.getBankQuestionId(), reason.get(),
                        row.getTimesAsked(), round(row.skipRate()), round(row.shortAnswerRate()),
                        round(row.scoreVariance()), row.getCandidateFlags());
            }
        }

        if (retired > 0) {
            log.info("QuestionRetirement: retired {} of {} questions evaluated", retired, live.size());
        }
        return retired;
    }

    /**
     * Applies the rules in order of how actionable the resulting reason is.
     *
     * <p>Package-private so it can be tested directly against a row, without
     * standing up a repository to reach it.
     */
    Optional<RetirementReason> judge(QuestionTelemetry row) {
        if (row.getTimesAsked() < properties.getMinimumSample()) {
            return Optional.empty();
        }

        if (row.getCandidateFlags() >= properties.getCandidateFlagThreshold()) {
            return Optional.of(RetirementReason.CANDIDATE_FLAGGED);
        }
        if (row.skipRate() >= properties.getMaxSkipRate()) {
            return Optional.of(RetirementReason.HIGH_SKIP_RATE);
        }
        if (row.shortAnswerRate() >= properties.getMaxShortAnswerRate()) {
            return Optional.of(RetirementReason.SHORT_ANSWERS);
        }
        // Variance is judged only once enough candidates have actually been
        // scored on it — a question answered ten times but scored twice has a
        // variance computed from two points, which is not a distribution either.
        if (row.getScoredCount() >= properties.getMinimumSample()
                && row.scoreVariance() <= properties.getMinScoreVariance()) {
            return Optional.of(RetirementReason.NO_SCORE_VARIANCE);
        }
        return Optional.empty();
    }

    /**
     * How many questions each threshold <em>would</em> retire, without retiring
     * anything.
     *
     * <p>The console's threshold preview (§A7.2). Changing a retirement
     * threshold is a decision whose blast radius is invisible until it has
     * already happened; being able to ask "how many would this remove" first is
     * the difference between tuning and gambling.
     */
    @Transactional(readOnly = true)
    public long previewRetirementCount() {
        return repository.findLiveWithMinimumSample(properties.getMinimumSample()).stream()
                .filter(row -> judge(row).isPresent())
                .count();
    }

    private static int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.strip().split("\\s+").length;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
