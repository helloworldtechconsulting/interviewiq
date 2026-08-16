package com.interviewiq.ai.service;

import com.interviewiq.ai.domain.QuestionTelemetry;
import com.interviewiq.ai.domain.RetirementReason;
import com.interviewiq.ai.infrastructure.QuestionTelemetryRepository;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.shared.config.QuestionQualityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link QuestionRetirementService} — the feedback loop that replaces
 * recruiter review (INTIQ-93 item 4).
 *
 * <p>Exercises {@code judge} directly against constructed telemetry rows, since
 * the rules are the substance and a repository adds nothing to testing them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionRetirementServiceTest {

    @Mock QuestionTelemetryRepository repository;
    @Mock InterviewSessionRepository sessionRepository;

    private final QuestionQualityProperties props = new QuestionQualityProperties();

    private QuestionRetirementService service() {
        return new QuestionRetirementService(repository, props, sessionRepository);
    }

    private QuestionTelemetry row() {
        QuestionTelemetry t = new QuestionTelemetry();
        t.setJobOpeningId(UUID.randomUUID());
        t.setBankQuestionId("q1");
        t.setQuestionText("Describe a race condition you have fixed.");
        return t;
    }

    /** Answers with a spread of scores — a healthy, discriminating question. */
    private QuestionTelemetry healthy(int n) {
        QuestionTelemetry t = row();
        for (int i = 0; i < n; i++) {
            t.recordAnswer(false, 40, null);
            t.recordScore((short) (i % 9 + 1));   // scores 1..9, real variance
        }
        return t;
    }

    // =========================================================================
    // The sample gate
    // =========================================================================

    /**
     * The most important rule and the least obvious. A question asked twice and
     * skipped once has a 50% skip rate and means nothing; acting on it would
     * strip good questions from a bank early in an opening's life, when the bank
     * is smallest and can least afford it.
     */
    @Test
    void nothingIsJudgedBelowTheMinimumSample() {
        props.setMinimumSample(10);

        QuestionTelemetry t = row();
        t.recordAnswer(true, 0, null);
        t.recordAnswer(true, 0, null);   // 100% skip rate on two answers

        assertThat(service().judge(t)).isEmpty();
    }

    @Test
    void theSameQuestionIsRetiredOnceThereIsEnoughData() {
        props.setMinimumSample(10);
        props.setMaxSkipRate(0.4);

        QuestionTelemetry t = row();
        for (int i = 0; i < 10; i++) {
            t.recordAnswer(true, 0, null);
        }

        assertThat(service().judge(t)).contains(RetirementReason.HIGH_SKIP_RATE);
    }

    // =========================================================================
    // The individual signals
    // =========================================================================

    @Test
    void aQuestionEveryoneSkipsIsRetired() {
        props.setMinimumSample(5);
        props.setMaxSkipRate(0.4);

        QuestionTelemetry t = row();
        for (int i = 0; i < 5; i++) t.recordAnswer(true, 0, null);
        for (int i = 0; i < 5; i++) { t.recordAnswer(false, 40, null); t.recordScore((short) (i + 1)); }

        assertThat(t.skipRate()).isEqualTo(0.5);
        assertThat(service().judge(t)).contains(RetirementReason.HIGH_SKIP_RATE);
    }

    @Test
    void aQuestionDrawingOneWordAnswersIsRetired() {
        props.setMinimumSample(5);
        props.setMaxShortAnswerRate(0.5);

        QuestionTelemetry t = row();
        for (int i = 0; i < 8; i++) { t.recordAnswer(false, 3, null); t.recordScore((short) (i % 9 + 1)); }

        assertThat(service().judge(t)).contains(RetirementReason.SHORT_ANSWERS);
    }

    /**
     * The signal no human would spot by reading. A question every candidate
     * scores 7/10 on is well written, on topic, and useless: it costs 90 seconds
     * of every interview and moves nobody's ranking.
     */
    @Test
    void aQuestionEveryCandidateScoresTheSameOnIsRetired() {
        props.setMinimumSample(10);
        props.setMinScoreVariance(0.5);

        QuestionTelemetry t = row();
        for (int i = 0; i < 12; i++) {
            t.recordAnswer(false, 60, null);
            t.recordScore((short) 7);   // identical every time
        }

        assertThat(t.scoreVariance()).isEqualTo(0.0);
        assertThat(service().judge(t)).contains(RetirementReason.NO_SCORE_VARIANCE);
    }

    @Test
    void aQuestionCandidatesFlagIsRetired() {
        props.setMinimumSample(5);
        props.setCandidateFlagThreshold(3);

        QuestionTelemetry t = healthy(10);
        t.recordCandidateFlag();
        t.recordCandidateFlag();
        t.recordCandidateFlag();

        assertThat(service().judge(t)).contains(RetirementReason.CANDIDATE_FLAGGED);
    }

    @Test
    void aHealthyQuestionSurvives() {
        props.setMinimumSample(10);

        assertThat(service().judge(healthy(20))).isEmpty();
    }

    // =========================================================================
    // Ordering
    // =========================================================================

    /**
     * A question nobody answers also has no variance, arithmetically. The
     * recorded reason should be the one a human can act on — "everyone skips
     * this" — not the derived one.
     */
    @Test
    void skipRateIsReportedAheadOfVarianceWhenBothApply() {
        props.setMinimumSample(5);
        props.setMaxSkipRate(0.4);
        props.setMinScoreVariance(0.5);

        QuestionTelemetry t = row();
        for (int i = 0; i < 9; i++) t.recordAnswer(true, 0, null);
        t.recordAnswer(false, 50, null);
        t.recordScore((short) 7);

        assertThat(service().judge(t)).contains(RetirementReason.HIGH_SKIP_RATE);
    }

    // =========================================================================
    // Welford correctness
    // =========================================================================

    /**
     * The running variance has to match the textbook value, because a systematic
     * error here retires good questions or keeps useless ones — and either
     * failure is invisible.
     */
    @Test
    void theRunningVarianceMatchesTheSampleVariance() {
        QuestionTelemetry t = row();
        short[] scores = {2, 4, 4, 4, 5, 5, 7, 9};
        for (short s : scores) {
            t.recordAnswer(false, 40, null);
            t.recordScore(s);
        }

        // mean 5, sum of squared deviations 32, sample variance 32/7
        assertThat(t.getScoreMean()).isCloseTo(5.0, within(1e-9));
        assertThat(t.scoreVariance()).isCloseTo(32.0 / 7.0, within(1e-9));
    }

    @Test
    void varianceIsZeroUntilThereAreTwoScores() {
        QuestionTelemetry t = row();
        t.recordAnswer(false, 40, null);
        t.recordScore((short) 8);

        assertThat(t.scoreVariance()).isEqualTo(0.0);
    }

    /**
     * A skip is one event, not two. Counting it as a short answer as well would
     * double-penalise it and make the two signals indistinguishable.
     */
    @Test
    void aSkipIsNotAlsoCountedAsAShortAnswer() {
        QuestionTelemetry t = row();
        t.recordAnswer(true, 0, null);

        assertThat(t.getTimesAsked()).isEqualTo(1);
        assertThat(t.getTimesSkipped()).isEqualTo(1);
        assertThat(t.getShortAnswers()).isZero();
        assertThat(t.getScoredCount()).isZero();
    }

    // =========================================================================
    // Retirement bookkeeping
    // =========================================================================

    @Test
    void retirementRecordsAReasonAndIsIdempotent() {
        QuestionTelemetry t = row();
        t.retire(RetirementReason.HIGH_SKIP_RATE);
        var firstAt = t.getRetiredAt();

        t.retire(RetirementReason.NO_SCORE_VARIANCE);

        assertThat(t.isRetired()).isTrue();
        assertThat(t.getRetiredReason()).isEqualTo(RetirementReason.HIGH_SKIP_RATE);
        assertThat(t.getRetiredAt()).isEqualTo(firstAt);
    }

    @Test
    void reinstateClearsBothHalvesOfTheRetirement() {
        QuestionTelemetry t = row();
        t.retire(RetirementReason.MANUAL);

        t.reinstate();

        assertThat(t.isRetired()).isFalse();
        assertThat(t.getRetiredReason()).isNull();
        assertThat(t.getRetiredAt()).isNull();
    }

    /** Follow-ups and employer questions have no bank id and are not ours to retire. */
    @Test
    void answersWithNoBankIdAreIgnored() {
        service().recordAnswer(UUID.randomUUID(), null, "text", false, "an answer", (short) 5);
        service().recordAnswer(UUID.randomUUID(), "  ", "text", false, "an answer", (short) 5);

        assertThat(repository.findAll()).isEmpty();
    }
}
