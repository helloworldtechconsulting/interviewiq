package com.interviewiq.session.domain;

/**
 * Classification of a row in the domain event log (PRD v2.1, Implementation
 * Architecture Decisions §2 — INTIQ-98).
 *
 * <p>DB CHECK values: {@code 'SUBFLOW'}, {@code 'CALL'}, {@code 'DECISION'},
 * {@code 'STATE'}, {@code 'SIGNAL'} (see V048).
 */
public enum SpanKind {

    /** A named unit of work containing other spans. A top-level subflow has no parent. */
    SUBFLOW,

    /** An outbound call to something external — an LLM, object storage, SMTP, Razorpay. */
    CALL,

    /**
     * A branch point.
     *
     * <p>Decisions are first-class in this log, and they record the branches
     * <em>not</em> taken as well as the one that was. That is what lets a trace
     * explain itself rather than merely list events — "why was no follow-up
     * asked here?" becomes an answerable question.
     */
    DECISION,

    /** A session state transition. */
    STATE,

    /** An inbound event — a WebSocket message, a webhook delivery, a timer firing. */
    SIGNAL
}
