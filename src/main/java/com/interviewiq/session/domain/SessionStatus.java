package com.interviewiq.session.domain;

import java.util.Set;

/**
 * Interview session lifecycle states (PRD v2.1 §7.4.4).
 *
 * <p>DB CHECK values: {@code 'INVITED'}, {@code 'SCHEDULED'}, {@code 'IN_PROGRESS'},
 * {@code 'EVALUATING'}, {@code 'COMPLETED'}, {@code 'EXPIRED'}, {@code 'ERROR'},
 * {@code 'CANCELLED'} (see V042).
 *
 * <pre>
 *   INVITED     → SCHEDULED, IN_PROGRESS, EXPIRED, CANCELLED
 *   SCHEDULED   → IN_PROGRESS, EXPIRED, CANCELLED
 *   IN_PROGRESS → EVALUATING, ERROR
 *   EVALUATING  → COMPLETED, ERROR
 *   COMPLETED   → (terminal)
 *   EXPIRED     → (terminal)
 *   ERROR       → (terminal — requires manual intervention)
 *   CANCELLED   → (terminal)
 * </pre>
 *
 * <p>{@code INVITED → IN_PROGRESS} skips {@code SCHEDULED} entirely, which is the
 * "Start now" path: when questions are ready and capacity is free, there is no
 * artificial wait (§7.4.3).
 */
public enum SessionStatus {

    /** Link sent, not yet scheduled. ₹100 reserved from the wallet; invite emailed. */
    INVITED,

    /**
     * The candidate has chosen a time; capacity buckets are occupied and the
     * confirmation email with its {@code .ics} attachment has been sent.
     *
     * <p>New in v2.1, and it exists because the candidate now books for
     * themselves — so there is a real interval between invite and start that the
     * recruiter should be able to see.
     */
    SCHEDULED,

    /**
     * The candidate is in the interview room. The server-side hard timer for the
     * job's duration tier is running and pod eviction protection is held.
     */
    IN_PROGRESS,

    /**
     * The interview has finished and scoring is running.
     *
     * <p>This state is user-visible by design. The candidate is explicitly told
     * the interview is complete, and the recruiter is explicitly shown that
     * scoring is still running. The PRD is direct about this: do not hide it
     * behind a spinner on the report page, because recruiters run hiring drives
     * and need to know which reports are still pending.
     */
    EVALUATING,

    /** Report ready. ₹100 settled, recruiter emailed, eviction protection released. */
    COMPLETED,

    /** Invite TTL elapsed unstarted. The reservation is released and held buckets freed. */
    EXPIRED,

    /** Employer cancelled before start. Reservation released, buckets freed, link invalidated. */
    CANCELLED,

    /** System fault — evaluation failed after retries, or an unrecoverable room fault. */
    ERROR;

    private static final Set<SessionStatus> TERMINAL =
            Set.of(COMPLETED, EXPIRED, CANCELLED, ERROR);

    /** States from which no further transition is valid. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Whether a session in this state is holding capacity buckets that must be
     * released when it ends. A booked-but-not-started session occupies buckets;
     * once the interview is under way the buckets have served their purpose.
     */
    public boolean holdsCapacity() {
        return this == SCHEDULED;
    }

    /**
     * Whether the session counts toward "pending interviews" on the employer
     * dashboard (PRD §7.7).
     */
    public boolean isPending() {
        return this == INVITED || this == SCHEDULED || this == IN_PROGRESS || this == EVALUATING;
    }

    /** Valid successor states, per the state machine in §7.4.4. */
    public Set<SessionStatus> allowedTransitions() {
        return switch (this) {
            case INVITED     -> Set.of(SCHEDULED, IN_PROGRESS, EXPIRED, CANCELLED);
            case SCHEDULED   -> Set.of(IN_PROGRESS, EXPIRED, CANCELLED);
            case IN_PROGRESS -> Set.of(EVALUATING, ERROR);
            case EVALUATING  -> Set.of(COMPLETED, ERROR);
            case COMPLETED, EXPIRED, CANCELLED, ERROR -> Set.of();
        };
    }

    public boolean canTransitionTo(SessionStatus target) {
        return allowedTransitions().contains(target);
    }
}
