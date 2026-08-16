package com.interviewengine.job.domain;

/**
 * Result of the prohibited-topic safety filter on an employer-supplied question
 * (PRD v2.1 §7.5.8).
 *
 * <p>DB CHECK values: {@code 'PENDING'}, {@code 'APPROVED'}, {@code 'REJECTED'}
 * (see V044).
 *
 * <p>The filter applies to employer questions exactly as it does to generated
 * ones. If a customer uploads "Are you planning to have children?", we refuse it
 * and tell them why — our platform, our liability, our reputation. The filter is
 * not optional just because a human wrote the question rather than a model.
 *
 * <p>Employer questions bypass the <em>quality</em> critic but never the
 * <em>safety</em> filter, and there is no override. That is why this enum has no
 * "approved by override" state: the only way out of {@link #REJECTED} is to
 * change the question text.
 */
public enum QuestionSafetyStatus {

    /** Uploaded; the safety filter has not run yet. Not eligible for a question bank. */
    PENDING,

    /** Cleared the prohibited-topic filter. Eligible to occupy the core segment. */
    APPROVED,

    /**
     * Refused. The rejection names the prohibited category — age, gender,
     * religion, caste, marital status or pregnancy — so the employer can correct
     * the question rather than guess at what was wrong with it.
     */
    REJECTED
}
