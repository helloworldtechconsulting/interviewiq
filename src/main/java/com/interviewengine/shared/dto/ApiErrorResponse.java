package com.interviewengine.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Canonical error envelope for all InterviewEngine API error responses.
 *
 * <p>Structure emitted on any non-2xx response:
 * <pre>
 * {
 *   "success": false,
 *   "errorCode": "RESOURCE_NOT_FOUND",
 *   "message": "Interview session abc-123 not found",
 *   "timestamp": "2025-06-01T10:00:00Z",
 *   "fieldErrors": [                       // present only on validation failures
 *     { "field": "email", "message": "must be a well-formed email address" }
 *   ]
 * }
 * </pre>
 *
 * <p>{@code fieldErrors} is omitted (not {@code null}) when there are no
 * field-level validation violations, keeping the response compact for
 * non-validation errors.
 *
 * @param success     always {@code false} for error responses
 * @param errorCode   machine-readable error identifier (matches {@link com.interviewengine.shared.exception.ErrorCode})
 * @param message     human-readable description — safe to surface to API consumers
 * @param timestamp   UTC instant when the error occurred
 * @param fieldErrors per-field validation details; {@code null} / omitted when not applicable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        boolean success,
        String errorCode,
        String message,
        Instant timestamp,
        List<FieldError> fieldErrors
) {

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /** Error without field-level detail (most error types). */
    public static ApiErrorResponse of(String errorCode, String message) {
        return new ApiErrorResponse(false, errorCode, message, Instant.now(), null);
    }

    /** Validation error with per-field details. */
    public static ApiErrorResponse ofValidation(String message, List<FieldError> fieldErrors) {
        return new ApiErrorResponse(false, ErrorCode.VALIDATION_ERROR, message, Instant.now(), fieldErrors);
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * A single field-level validation failure.
     *
     * @param field   the dot-notation path to the failing field (e.g. {@code "jobOpening.title"})
     * @param message the constraint violation message
     */
    public record FieldError(String field, String message) {}

    /**
     * Centralised machine-readable error code constants.
     *
     * <p>Kept as a nested class so the canonical list lives alongside the response
     * DTO rather than scattered across exception classes.
     */
    public static final class ErrorCode {
        private ErrorCode() {}

        // 400
        public static final String VALIDATION_ERROR          = "VALIDATION_ERROR";
        public static final String INVALID_REQUEST           = "INVALID_REQUEST";

        // 401
        public static final String UNAUTHORIZED              = "UNAUTHORIZED";
        public static final String TOKEN_EXPIRED             = "TOKEN_EXPIRED";
        public static final String INVALID_TOKEN             = "INVALID_TOKEN";

        // 403
        public static final String FORBIDDEN                 = "FORBIDDEN";

        // 404
        public static final String RESOURCE_NOT_FOUND        = "RESOURCE_NOT_FOUND";

        // 409
        public static final String CONFLICT                  = "CONFLICT";

        // 422 — domain logic violations
        public static final String INSUFFICIENT_BALANCE      = "INSUFFICIENT_BALANCE";
        public static final String SESSION_STATE_INVALID     = "SESSION_STATE_INVALID";

        // 429
        public static final String RATE_LIMIT_EXCEEDED       = "RATE_LIMIT_EXCEEDED";

        // 502 / 503 — external service failures
        public static final String EXTERNAL_SERVICE_ERROR    = "EXTERNAL_SERVICE_ERROR";
        public static final String AI_SERVICE_ERROR          = "AI_SERVICE_ERROR";
        public static final String PAYMENT_SERVICE_ERROR     = "PAYMENT_SERVICE_ERROR";
        public static final String STORAGE_SERVICE_ERROR     = "STORAGE_SERVICE_ERROR";

        // 500
        public static final String INTERNAL_ERROR            = "INTERNAL_ERROR";
    }
}
