package com.interviewiq.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown for semantic / business-rule validation failures that fall outside
 * Bean Validation's scope (i.e., checks that require DB or service-layer context).
 *
 * <p>Maps to HTTP 400 Bad Request.
 *
 * <p>For Bean Validation failures ({@code @Valid} / {@code @Validated}) the
 * {@link com.interviewiq.shared.web.GlobalExceptionHandler} handles
 * {@code MethodArgumentNotValidException} directly and produces field-level
 * errors — you should NOT throw this exception for those cases.
 *
 * <p>Use this exception when:
 * <ul>
 *   <li>Business rules reject the input (e.g. invite expiry must be in the future)</li>
 *   <li>State pre-conditions are not met but it is the caller's fault
 *       (e.g. uploading a resume when extraction is still IN_PROGRESS)</li>
 * </ul>
 */
public class ValidationException extends InterviewIqException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.BAD_REQUEST;
    }

    @Override
    public String getErrorCode() {
        return com.interviewiq.shared.dto.ApiErrorResponse.ErrorCode.INVALID_REQUEST;
    }
}
