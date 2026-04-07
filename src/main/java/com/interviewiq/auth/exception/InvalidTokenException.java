package com.interviewiq.auth.exception;

import com.interviewiq.shared.dto.ApiErrorResponse;
import com.interviewiq.shared.exception.InterviewIqException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a token fails signature verification, has a malformed structure,
 * contains unexpected claims, or is used after being revoked.
 *
 * <p>Maps to HTTP 401 Unauthorized with error code {@code INVALID_TOKEN}.
 *
 * <p>Do not expose the underlying JJWT exception message in the constructor
 * argument — it may contain information useful to an attacker.  Use a generic
 * message and log the root cause separately.
 */
public class InvalidTokenException extends InterviewIqException {

    public InvalidTokenException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNAUTHORIZED;
    }

    @Override
    public String getErrorCode() {
        return ApiErrorResponse.ErrorCode.INVALID_TOKEN;
    }
}
