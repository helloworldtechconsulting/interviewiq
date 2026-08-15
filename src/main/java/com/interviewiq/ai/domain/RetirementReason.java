package com.interviewiq.ai.domain;

/**
 * Why a question was taken out of an opening's bank (INTIQ-93, item 4).
 *
 * <p>Recorded rather than inferred. A retirement without a stated reason cannot
 * be audited, cannot be argued with, and cannot be reversed with any confidence
 * — and the console needs to show a human why the system removed something
 * before asking them whether it was right.
 */
public enum RetirementReason {

    /** Skipped by too many candidates — confusing, or unanswerable aloud. */
    HIGH_SKIP_RATE,

    /** Draws sub-five-word answers — closed, or too vague to answer at length. */
    SHORT_ANSWERS,

    /**
     * Near-zero score variance across candidates.
     *
     * <p>The strongest signal and the one no human would spot by reading. A
     * question every candidate scores 7/10 on is well written, on topic, and
     * completely useless: it costs 90 seconds of every interview and contributes
     * nothing to the ranking the product is bought for.
     */
    NO_SCORE_VARIANCE,

    /** Flagged by candidates often enough to warrant removal. */
    CANDIDATE_FLAGGED,

    /** Removed by a staff member through the internal console. */
    MANUAL
}
