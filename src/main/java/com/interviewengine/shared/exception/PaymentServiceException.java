package com.interviewengine.shared.exception;

/**
 * Thrown when a Razorpay API call fails — order creation, payment capture,
 * refund initiation, etc.
 *
 * <p>Inherits HTTP 502 Bad Gateway from {@link ExternalServiceException}.
 */
public class PaymentServiceException extends ExternalServiceException {

    public PaymentServiceException(String message) {
        super(message);
    }

    public PaymentServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getErrorCode() {
        return com.interviewengine.shared.dto.ApiErrorResponse.ErrorCode.PAYMENT_SERVICE_ERROR;
    }
}
