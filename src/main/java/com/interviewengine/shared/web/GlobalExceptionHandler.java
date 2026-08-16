package com.interviewengine.shared.web;

import com.interviewengine.shared.dto.ApiErrorResponse;
import com.interviewengine.shared.dto.ApiErrorResponse.ErrorCode;

import com.interviewengine.shared.exception.InterviewEngineException;
import com.interviewengine.shared.exception.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Central exception handler for all REST controllers.
 *
 * <p>Handler priority (highest to lowest):
 * <ol>
 *   <li>Application-specific {@link InterviewEngineException} subtypes</li>
 *   <li>Spring MVC input-parsing exceptions</li>
 *   <li>Spring Security exceptions (defence-in-depth; filter chain handles most)</li>
 *   <li>Generic {@link Exception} catch-all — never leaks internal detail</li>
 * </ol>
 *
 * <p>Logging strategy:
 * <ul>
 *   <li>4xx errors → DEBUG (expected, high-volume; not actionable for ops)</li>
 *   <li>5xx / external service errors → ERROR with full stack trace</li>
 *   <li>Rate-limit (429) → DEBUG (extremely common on auth endpoints)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // =========================================================================
    // 1. Application domain exceptions
    // =========================================================================

    /**
     * Handles all {@link InterviewEngineException} subtypes.
     *
     * <p>Each subclass declares its own HTTP status and error code, so this single
     * handler covers the entire exception hierarchy without per-subclass methods.
     * The only special case is {@link RateLimitException}, which additionally
     * writes the {@code Retry-After} header.
     */
    @ExceptionHandler(InterviewEngineException.class)
    public ResponseEntity<ApiErrorResponse> handleInterviewEngineException(InterviewEngineException ex) {
        HttpStatus status = ex.getHttpStatus();

        if (status.is5xxServerError()) {
            log.error("Application exception [{}]: {}", ex.getErrorCode(), ex.getMessage(), ex);
        } else {
            log.debug("Application exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        }

        ApiErrorResponse body = ApiErrorResponse.of(ex.getErrorCode(), ex.getMessage());

        if (ex instanceof RateLimitException rle && rle.getRetryAfterSeconds() > 0) {
            return ResponseEntity.status(status)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(rle.getRetryAfterSeconds()))
                    .body(body);
        }

        return ResponseEntity.status(status).body(body);
    }

    // =========================================================================
    // 2. Bean Validation — @Valid / @Validated
    // =========================================================================

    /**
     * Handles {@code @RequestBody} validation failures, producing structured
     * per-field errors so the client knows exactly which fields to correct.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ApiErrorResponse.FieldError(fe.getField(), defaultMessage(fe)))
                .toList();

        log.debug("Validation failed: {} field error(s)", fieldErrors.size());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.ofValidation("Request validation failed", fieldErrors));
    }

    // =========================================================================
    // 3. Spring MVC input-parsing exceptions
    // =========================================================================

    /** Malformed JSON body or unreadable request. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Unreadable request body: {}", ex.getMessage());
        return badRequest(ErrorCode.INVALID_REQUEST, "Request body is malformed or missing");
    }

    /** Path variable or request parameter type mismatch (e.g. non-UUID where UUID expected). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Invalid value '%s' for parameter '%s'".formatted(ex.getValue(), ex.getName());
        log.debug("Type mismatch: {}", message);
        return badRequest(ErrorCode.INVALID_REQUEST, message);
    }

    /** Required query / path parameter is absent. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        String message = "Required parameter '%s' is missing".formatted(ex.getParameterName());
        log.debug("Missing parameter: {}", message);
        return badRequest(ErrorCode.INVALID_REQUEST, message);
    }

/** Content-Type not accepted by this endpoint. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiErrorResponse.of(ErrorCode.INVALID_REQUEST,
                        "Content-Type '%s' is not supported".formatted(ex.getContentType())));
    }

    /** Static resource or route not found (Spring 6+ replaces NoHandlerFoundException). */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("No resource found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND,
                        "The requested path was not found"));
    }

    // =========================================================================
    // 4. Spring Security exceptions (defence-in-depth)
    // =========================================================================

    /**
     * Handles authentication failures that slip past the security filter chain.
     * In practice the filter chain's {@code AuthenticationEntryPoint} handles
     * these first; this is a safety net.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        log.debug("Authentication failure: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of(ErrorCode.UNAUTHORIZED, "Authentication required"));
    }

    /**
     * Handles authorisation failures for method-level {@code @PreAuthorize} checks.
     * Controller-level access is enforced by the security filter chain.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        log.debug("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(ErrorCode.FORBIDDEN, "Access denied"));
    }

    // =========================================================================
    // 5. Catch-all — never leaks internal detail
    // =========================================================================

    /**
     * Last-resort handler for any exception not caught above.
     *
     * <p>A correlation ID ({@link UUID#randomUUID()}) is generated so that ops
     * can correlate a user-visible "reference ID" with the server-side log line.
     * The original exception message is never surfaced to the caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unexpected error [correlationId={}]", correlationId, ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR,
                        "An unexpected error occurred. Reference ID: " + correlationId));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private ResponseEntity<ApiErrorResponse> badRequest(String errorCode, String message) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(errorCode, message));
    }

    private String defaultMessage(FieldError fe) {
        return fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value";
    }
}
