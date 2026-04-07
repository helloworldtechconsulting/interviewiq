package com.interviewiq.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a create or update operation violates a uniqueness invariant.
 *
 * <p>Maps to HTTP 409 Conflict.
 *
 * <p>Common triggers:
 * <ul>
 *   <li>Registering a company with a slug that is already in use</li>
 *   <li>Creating a user with an email address that already exists</li>
 *   <li>Dispatching a second bot to a session that already has one</li>
 * </ul>
 */
public class ConflictException extends InterviewIqException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.CONFLICT;
    }

    @Override
    public String getErrorCode() {
        return com.interviewiq.shared.dto.ApiErrorResponse.ErrorCode.CONFLICT;
    }
}
