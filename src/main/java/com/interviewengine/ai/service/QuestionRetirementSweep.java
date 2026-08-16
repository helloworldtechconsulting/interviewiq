package com.interviewengine.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the auto-retirement rules (INTIQ-93 item 4).
 *
 * <p>Daily rather than continuously. Retirement is a judgement about an
 * accumulating distribution, and the distribution does not change meaningfully
 * between one interview and the next — running it every five minutes would
 * re-evaluate the same unchanged rows all day for the same answer.
 *
 * <p>No claim discipline here, deliberately, unlike the other sweeps. The
 * operation is idempotent: {@link com.interviewengine.ai.domain.QuestionTelemetry#retire}
 * keeps the first reason, so two pods running the sweep concurrently retire the
 * same questions for the same reasons and the second write changes nothing. A
 * claim would add machinery to protect against an outcome that is already
 * harmless.
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class QuestionRetirementSweep {

    private static final Logger log = LoggerFactory.getLogger(QuestionRetirementSweep.class);

    private final QuestionRetirementService retirementService;

    public QuestionRetirementSweep(QuestionRetirementService retirementService) {
        this.retirementService = retirementService;
    }

    @Scheduled(cron = "${app.question-quality.sweep-cron:0 0 3 * * *}")
    public void sweep() {
        try {
            int retired = retirementService.evaluateAndRetire();
            if (retired > 0) {
                // Logged at info even when zero would be noise, because a
                // retirement changes what future candidates are asked and is
                // worth being able to correlate against a score shift later.
                log.info("QuestionRetirementSweep: retired {} question(s)", retired);
            }
        } catch (RuntimeException e) {
            log.error("QuestionRetirementSweep failed", e);
        }
    }
}
