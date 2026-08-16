package com.interviewengine.email.domain;

/** DB CHECK values: 'QUEUED', 'SENT', 'FAILED', 'BOUNCED', 'SUPPRESSED' */
public enum EmailStatus {

    /** Row created, send not yet attempted. */
    QUEUED,

    /** Handed to the SMTP provider, which accepted it. */
    SENT,

    /** Send was attempted and the provider refused it. Transient; retryable. */
    FAILED,

    /** Accepted at send time, then rejected downstream. Reported by webhook. */
    BOUNCED,

    /**
     * Deliberately not attempted — the address is on the suppression list.
     *
     * <p>Distinct from {@link #FAILED} on purpose: FAILED means the send broke,
     * SUPPRESSED means we chose not to send. Alerting on the two should differ,
     * and so should the answer given to a customer asking where their email is.
     */
    SUPPRESSED
}
