package com.interviewengine.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for failures caused by third-party services (OpenAI, Anthropic,
 * Razorpay, AWS, etc.).
 *
 * <p>Maps to HTTP 502 Bad Gateway by default, indicating that the InterviewEngine
 * server received an invalid or no response from an upstream service.
 *
 * <p>Concrete subclasses are provided for each integration point:
 * <ul>
 *   <li>{@link AiServiceException}      — OpenAI / Spring AI failures</li>
 *   <li>{@link PaymentServiceException} — Razorpay failures</li>
 *   <li>{@link StorageServiceException} — AWS S3 / storage failures</li>
 * </ul>
 *
 * <p>Always wrap the original exception as the {@code cause} so that the
 * service-layer logs contain the root cause, even though it is never sent to
 * the API consumer.
 */
public class ExternalServiceException extends InterviewEngineException {

    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.BAD_GATEWAY;
    }

    @Override
    public String getErrorCode() {
        return com.interviewengine.shared.dto.ApiErrorResponse.ErrorCode.EXTERNAL_SERVICE_ERROR;
    }
}
