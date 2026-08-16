package com.interviewengine.candidate.domain;

/**
 * Lifecycle of a bulk candidate import (PRD v2.1 §7.3.1).
 *
 * <p>DB CHECK values: {@code 'VALIDATING'}, {@code 'PREVIEW'}, {@code 'IMPORTING'},
 * {@code 'COMPLETED'}, {@code 'REJECTED'} (see V045).
 */
public enum ImportBatchStatus {

    /** CSV uploaded; rows are being parsed and checked. */
    VALIDATING,

    /**
     * Counts computed, awaiting the recruiter's confirmation.
     *
     * <p>Nothing is charged before this point. The preview states exactly what
     * will happen — "47 valid, 3 duplicates, 2 invalid emails" — and lets the
     * recruiter fix or skip rows before committing.
     */
    PREVIEW,

    /** Confirmed: the whole-batch wallet reservation is taken and rows are being written. */
    IMPORTING,

    COMPLETED,

    /**
     * Refused, most often for insufficient balance.
     *
     * <p>An import that runs out of money partway is a support ticket and a
     * half-imported opening, so the batch is refused in full with a top-up prompt
     * rather than failing at candidate 38.
     */
    REJECTED
}
