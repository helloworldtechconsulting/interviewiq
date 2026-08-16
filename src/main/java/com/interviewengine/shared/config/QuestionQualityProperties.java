package com.interviewengine.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auto-retirement thresholds (PRD v2.1 §7.5, INTIQ-93 item 4).
 *
 * <p>Bound to {@code app.question-quality}.
 *
 * <p>Every value here is a starting guess, and the story that asked for them
 * said so: "auto-retirement threshold configurable". They are configurable
 * precisely because the right numbers are an empirical question that cannot be
 * answered before the system has run — and the console's threshold preview
 * exists so they can be changed with the blast radius visible in advance.
 */
@ConfigurationProperties(prefix = "app.question-quality")
public class QuestionQualityProperties {

    /**
     * How many times a question must have been asked before any rule applies.
     *
     * <p>The most important number here, and the least obvious. Every rule below
     * is a judgement about a distribution, and a distribution of three is not
     * one — a question asked twice and skipped once has a 50% skip rate and means
     * nothing. Retiring on that would strip good questions out of a bank early in
     * an opening's life, exactly when it is smallest and can least afford it.
     */
    private int minimumSample = 10;

    /** Skipped by this fraction of candidates or more — confusing or unanswerable aloud. */
    private double maxSkipRate = 0.4;

    /** Draws sub-five-word answers this often or more — closed, or too vague. */
    private double maxShortAnswerRate = 0.5;

    /**
     * Score variance at or below which a question is considered not to
     * discriminate.
     *
     * <p>On a 0–10 per-question scale, a variance of 0.5 is a standard deviation
     * of about 0.7 — meaning essentially every candidate lands within a point of
     * the same score. That question is well written and worthless: it costs 90
     * seconds of every interview and moves nobody's ranking.
     */
    private double minScoreVariance = 0.5;

    /** Candidate flags at or above which a question is pulled regardless of its statistics. */
    private int candidateFlagThreshold = 3;

    /** How often the retirement sweep runs. */
    private String sweepCron = "0 0 3 * * *";

    public int getMinimumSample() { return minimumSample; }
    public void setMinimumSample(int minimumSample) { this.minimumSample = minimumSample; }

    public double getMaxSkipRate() { return maxSkipRate; }
    public void setMaxSkipRate(double maxSkipRate) { this.maxSkipRate = maxSkipRate; }

    public double getMaxShortAnswerRate() { return maxShortAnswerRate; }
    public void setMaxShortAnswerRate(double v) { this.maxShortAnswerRate = v; }

    public double getMinScoreVariance() { return minScoreVariance; }
    public void setMinScoreVariance(double minScoreVariance) { this.minScoreVariance = minScoreVariance; }

    public int getCandidateFlagThreshold() { return candidateFlagThreshold; }
    public void setCandidateFlagThreshold(int candidateFlagThreshold) { this.candidateFlagThreshold = candidateFlagThreshold; }

    public String getSweepCron() { return sweepCron; }
    public void setSweepCron(String sweepCron) { this.sweepCron = sweepCron; }
}
