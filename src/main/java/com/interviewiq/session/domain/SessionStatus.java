package com.interviewiq.session.domain;

/**
 * Interview session lifecycle states.
 * DB CHECK values: 'INVITED', 'STARTED', 'COMPLETED', 'EXPIRED', 'ERROR', 'CANCELLED'
 *
 * <p>Valid transitions (enforced by {@code SessionStateMachine}):
 * <pre>
 *   INVITED  → STARTED, EXPIRED, CANCELLED
 *   STARTED  → COMPLETED, ERROR
 *   COMPLETED → (terminal)
 *   EXPIRED   → (terminal)
 *   ERROR     → (terminal — requires manual intervention)
 *   CANCELLED → (terminal)
 * </pre>
 */
public enum SessionStatus {
    INVITED,
    STARTED,
    COMPLETED,
    EXPIRED,
    ERROR,
    CANCELLED
}
