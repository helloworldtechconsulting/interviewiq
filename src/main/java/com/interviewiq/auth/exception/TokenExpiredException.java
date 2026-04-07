package com.interviewiq.auth.exception;

import com.interviewiq.shared.dto.ApiErrorResponse;
import com.interviewiq.shared.exception.InterviewIqException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a JWT access token or invite token has passed its expiry timestamp.
 *
 * <p>Maps to HTTP 401 Unauthorized with error code {@code TOKEN_EXPIRED} so the
 * client can distinguish "token expired — refresh required" from "token invalid —
 * re-authenticate required".
 */
public class TokenExpiredException extends InterviewIqException {

    public TokenExpiredException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNAUTHORIZED;
    }

    @Override
    public String getErrorCode() {
        return ApiErrorResponse.ErrorCode.TOKEN_EXPIRED;
    }
}
