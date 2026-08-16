package com.interviewengine.ai.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Outcome statistics for one question in one opening's bank (INTIQ-93, V057).
 *
 * <p>This is the feedback loop that substitutes for the recruiter review the
 * product removed. The three shipped controls — critic pass, prohibited-topic
 * filter, automatic core selection — are all up-front gates. This is the only
 * one that learns from what questions actually did to real candidates.
 *
 * <p>DB table: {@code question_telemetry}
 */
@Entity
@Table(name = "question_telemetry")
public class QuestionTelemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "job_opening_id", nullable = false, updatable = false)
    private UUID jobOpeningId;

    /** Bank-local id, e.g. {@code q17}. Meaningful only within its own bank. */
    @Column(name = "bank_question_id", nullable = false, length = 50, updatable = false)
    private String bankQuestionId;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "times_asked", nullable = false)
    private int timesAsked = 0;

    @Column(name = "times_skipped", nullable = false)
    private int timesSkipped = 0;

    @Column(name = "short_answers", nullable = false)
    private int shortAnswers = 0;

    @Column(name = "candidate_flags", nullable = false)
    private int candidateFlags = 0;

    // ── Welford's online variance ────────────────────────────────────────────

    @Column(name = "scored_count", nullable = false)
    private int scoredCount = 0;

    @Column(name = "score_mean", nullable = false)
    private double scoreMean = 0.0;

    @Column(name = "score_m2", nullable = false)
    private double scoreM2 = 0.0;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "retired_reason", length = 50)
    private RetirementReason retiredReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // =========================================================================
    // Behaviour
    // =========================================================================

    /**
     * Folds one answer's outcome into the running statistics.
     *
     * <p>Welford's algorithm for the variance: numerically stable, and it keeps
     * one row per question no matter how many candidates answer it. Storing every
     * score and aggregating on read would add a row per answer per question
     * forever, for a number that is only ever read by a sweep.
     *
     * <p>The score is deliberately <em>not</em> taken here even when one is
     * available. It arrives minutes later from the evaluation path, and
     * {@link #recordScore} is the other half of the record.
     *
     * @param skipped     whether the candidate gave no answer
     * @param wordCount   words in the answer, for the too-short signal
     * @param score       the score if already known, usually null at this point
     */
    public void recordAnswer(boolean skipped, int wordCount, Short score) {
        timesAsked++;

        if (skipped) {
            timesSkipped++;
            // A skipped question has no answer to be short and no score to vary.
            // Counting it as a short answer too would double-penalise the same
            // event and make the two signals indistinguishable.
            return;
        }

        if (wordCount > 0 && wordCount < SHORT_ANSWER_WORDS) {
            shortAnswers++;
        }

        if (score != null) {
            recordScore(score);
        }
    }

    /**
     * Folds one score into the running mean and M2 (Welford).
     *
     * <p>Called from the evaluation path once scoring has run. Numerically
     * stable, and it keeps one row per question no matter how many candidates
     * answer it — storing every score to aggregate on read would add a row per
     * answer per question forever, for a number only a nightly sweep looks at.
     */
    public void recordScore(short score) {
        scoredCount++;
        double delta = score - scoreMean;
        scoreMean += delta / scoredCount;
        scoreM2 += delta * (score - scoreMean);
    }

    /** §7.5 — under five words is the "could you elaborate" threshold. */
    public static final int SHORT_ANSWER_WORDS = 5;

    public void recordCandidateFlag() {
        candidateFlags++;
    }

    /**
     * Sample variance of the scores this question produced, or 0 when there is
     * not enough data to have one.
     *
     * <p>Sample rather than population variance ({@code n-1}), because the
     * candidates who have answered are a sample of the candidates who will.
     */
    public double scoreVariance() {
        if (scoredCount < 2) {
            return 0.0;
        }
        return scoreM2 / (scoredCount - 1);
    }

    public double skipRate() {
        return timesAsked == 0 ? 0.0 : (double) timesSkipped / timesAsked;
    }

    public double shortAnswerRate() {
        return timesAsked == 0 ? 0.0 : (double) shortAnswers / timesAsked;
    }

    public boolean isRetired() {
        return retiredAt != null;
    }

    /** Retires the question, recording why. Idempotent — the first reason stands. */
    public void retire(RetirementReason reason) {
        if (retiredAt == null) {
            retiredAt = OffsetDateTime.now(ZoneOffset.UTC);
            retiredReason = reason;
        }
    }

    /** Un-retires a question the automatic rules took out too eagerly (console, §A7.2). */
    public void reinstate() {
        retiredAt = null;
        retiredReason = null;
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId() { return id; }

    public UUID getJobOpeningId() { return jobOpeningId; }
    public void setJobOpeningId(UUID jobOpeningId) { this.jobOpeningId = jobOpeningId; }

    public String getBankQuestionId() { return bankQuestionId; }
    public void setBankQuestionId(String bankQuestionId) { this.bankQuestionId = bankQuestionId; }

    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }

    public int getTimesAsked() { return timesAsked; }
    public int getTimesSkipped() { return timesSkipped; }
    public int getShortAnswers() { return shortAnswers; }
    public int getCandidateFlags() { return candidateFlags; }
    public int getScoredCount() { return scoredCount; }
    public double getScoreMean() { return scoreMean; }
    public double getScoreM2() { return scoreM2; }

    public OffsetDateTime getRetiredAt() { return retiredAt; }
    public RetirementReason getRetiredReason() { return retiredReason; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
