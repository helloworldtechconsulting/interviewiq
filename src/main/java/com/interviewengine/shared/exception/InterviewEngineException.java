package com.interviewengine.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Root of the InterviewEngine application exception hierarchy.
 *
 * <p>All domain-specific exceptions extend this class so that the
 * {@link com.interviewengine.shared.web.GlobalExceptionHandler} can catch the entire
 * hierarchy with a single handler and delegate HTTP status mapping to each
 * subclass via {@link #getHttpStatus()} and error code mapping via
 * {@link #getErrorCode()}.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Use a specific subclass whenever possible — catch the narrowest type.</li>
 *   <li>Never throw {@code InterviewEngineException} directly; always use a subtype.</li>
 *   <li>{@code message} must be safe to surface to API consumers — no stack traces,
 *       no internal identifiers, no PII beyond what the consumer already knows.</li>
 * </ul>
 */
public abstract class InterviewEngineException extends RuntimeException {

    /**
     * @param message consumer-safe error description
     */
    protected InterviewEngineException(String message) {
        super(message);
    }

    /**
     * @param message consumer-safe error description
     * @param cause   the underlying exception (logged, never propagated to the API)
     */
    protected InterviewEngineException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The HTTP status code this exception maps to.
     * Implemented by each concrete subclass.
     */
    public abstract HttpStatus getHttpStatus();

    /**
     * The machine-readable error code included in {@link com.interviewengine.shared.dto.ApiErrorResponse}.
     * Defaults to a safe fallback; subclasses override for precision.
     */
    public String getErrorCode() {
        return com.interviewengine.shared.dto.ApiErrorResponse.ErrorCode.INTERNAL_ERROR;
    }
}
