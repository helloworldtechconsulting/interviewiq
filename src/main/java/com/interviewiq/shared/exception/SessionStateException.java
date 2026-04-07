package com.interviewiq.shared.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown when an operation is attempted on an {@code InterviewSession} that is
 * in an incompatible lifecycle state.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity — the request is structurally valid
 * but the session state machine rejects the transition.
 *
 * <p>Examples:
 * <ul>
 *   <li>Dispatching a bot to a session whose status is {@code COMPLETED}</li>
 *   <li>Starting a session whose question generation status is {@code PENDING}</li>
 *   <li>Cancelling a session that has already {@code STARTED}</li>
 * </ul>
 */
public class SessionStateException extends InterviewIqException {

    public SessionStateException(String message) {
        super(message);
    }

    /**
     * Convenience factory for illegal state-transition messages.
     *
     * @param sessionId     the session UUID
     * @param currentStatus the session's current status string
     * @param attemptedOp   description of the operation that was rejected
     */
    public static SessionStateException illegalTransition(UUID sessionId,
                                                          String currentStatus,
                                                          String attemptedOp) {
        return new SessionStateException(
                "Cannot %s: session %s is in state %s".formatted(attemptedOp, sessionId, currentStatus)
        );
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    @Override
    public String getErrorCode() {
        return com.interviewiq.shared.dto.ApiErrorResponse.ErrorCode.SESSION_STATE_INVALID;
    }
}
