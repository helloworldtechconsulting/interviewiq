package com.interviewengine.shared.exception;

/**
 * Thrown when the AI pipeline (OpenAI / Spring AI) fails — either during
 * question generation or evaluation report generation.
 *
 * <p>Inherits HTTP 502 Bad Gateway from {@link ExternalServiceException}.
 */
public class AiServiceException extends ExternalServiceException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getErrorCode() {
        return com.interviewengine.shared.dto.ApiErrorResponse.ErrorCode.AI_SERVICE_ERROR;
    }
}
