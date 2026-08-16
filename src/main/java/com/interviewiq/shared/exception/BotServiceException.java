package com.interviewiq.shared.exception;

/**
 * Thrown when a Recall.ai API call fails — bot dispatch, bot status polling,
 * recording retrieval, etc.
 *
 * <p>Inherits HTTP 502 Bad Gateway from {@link ExternalServiceException}.
 */
public class BotServiceException extends ExternalServiceException {

    public BotServiceException(String message) {
        super(message);
    }

    public BotServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getErrorCode() {
        return com.interviewiq.shared.dto.ApiErrorResponse.ErrorCode.BOT_SERVICE_ERROR;
    }
}
