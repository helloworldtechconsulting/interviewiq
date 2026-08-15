package com.interviewiq.job.domain;

import java.time.Duration;

/**
 * Per-job interview length, chosen by the employer (PRD v2.1 §7.2.1).
 *
 * <p>Replaces the single hardcoded duration of the March design. The tier drives
 * three separate things, which is why it is a first-class type rather than a
 * loose integer:
 *
 * <ol>
 *   <li>the number of questions generated for the session (§7.5.1),</li>
 *   <li>the server-side hard timer enforced during the interview (§7.5.7),</li>
 *   <li>how many 5-minute capacity buckets a booking occupies (§7.4.2).</li>
 * </ol>
 *
 * <h2>All four tiers cost ₹100</h2>
 *
 * <p>There is deliberately no price on this enum. The marginal cost of a longer
 * interview is LLM tokens measured in paise and object storage measured in
 * fractions of a rupee — nothing that justifies a pricing tier. Per-minute
 * pricing would also push employers toward the wrong tier for the role. The tier
 * is a product-fit decision, not a monetisation lever.
 *
 * <p>The 60-minute ceiling of {@link #COMPREHENSIVE} is the absolute upper bound
 * carried over from the API reference; no tier may exceed it.
 */
public enum DurationTier {

    /** 20 minutes, 8 questions. High-volume funnels, fresher roles, first-pass filtering. */
    QUICK(20, 8),

    /** 35 minutes, 15 questions. The general-purpose first-round screen, and the default. */
    STANDARD(35, 15),

    /** 45 minutes, 20 questions. Mid-senior individual contributors and specialist roles. */
    IN_DEPTH(45, 20),

    /** 60 minutes, 26 questions. Senior and lead roles where the screen replaces a full technical call. */
    COMPREHENSIVE(60, 26);

    /** Width of one capacity bucket, in minutes (PRD §7.4.2). */
    public static final int BUCKET_MINUTES = 5;

    private final int minutes;
    private final int questionCount;

    DurationTier(int minutes, int questionCount) {
        this.minutes = minutes;
        this.questionCount = questionCount;
    }

    /** The default tier for a new job opening (PRD §7.2). */
    public static DurationTier defaultTier() {
        return STANDARD;
    }

    public int getMinutes() {
        return minutes;
    }

    /** How many questions the generation workflow must produce for this tier. */
    public int getQuestionCount() {
        return questionCount;
    }

    /** The server-side hard timer for an interview at this tier. */
    public Duration getHardTimeout() {
        return Duration.ofMinutes(minutes);
    }

    /**
     * How many consecutive 5-minute capacity buckets a booking at this tier
     * occupies — twelve for Comprehensive, four for Quick (PRD §7.4.2).
     */
    public int getBucketSpan() {
        return minutes / BUCKET_MINUTES;
    }
}
