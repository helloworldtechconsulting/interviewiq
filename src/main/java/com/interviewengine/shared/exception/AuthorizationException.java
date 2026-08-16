package com.interviewengine.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a caller attempts an operation they are not permitted to perform.
 *
 * <p>Maps to HTTP 403 Forbidden.
 *
 * <p>Distinction from authentication failures (401 Unauthorized):
 * <ul>
 *   <li>Use this exception when the caller <em>is</em> authenticated but lacks
 *       permission — e.g. an employer trying to access another company's data.</li>
 *   <li>Authentication failures (missing / expired JWT) are handled by the
 *       security filter chain, not by throwing this exception.</li>
 * </ul>
 */
public class AuthorizationException extends InterviewEngineException {

    public AuthorizationException(String message) {
        super(message);
    }

    /** Default message for generic cross-tenant access attempts. */
    public static AuthorizationException accessDenied() {
        return new AuthorizationException("Access denied");
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.FORBIDDEN;
    }

    @Override
    public String getErrorCode() {
        return com.interviewengine.shared.dto.ApiErrorResponse.ErrorCode.FORBIDDEN;
    }
}
