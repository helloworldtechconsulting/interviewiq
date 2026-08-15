package com.interviewiq.session.domain;

/**
 * How a span finished (PRD v2.1, Implementation Architecture Decisions §2).
 *
 * <p>DB CHECK values: {@code 'OK'}, {@code 'FAILED'}, {@code 'RETRY'},
 * {@code 'SKIPPED'}, {@code 'TIMEOUT'} (see V048). A span with a null outcome is
 * still open.
 */
public enum SpanOutcome {

    OK,

    /** Terminal failure of this span. */
    FAILED,

    /** Failed, but a further attempt followed — the retry is its own span. */
    RETRY,

    /** Deliberately not executed, e.g. a question skipped after 90 seconds of silence. */
    SKIPPED,

    TIMEOUT
}
