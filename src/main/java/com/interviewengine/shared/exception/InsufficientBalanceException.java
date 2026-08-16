package com.interviewengine.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a billing operation cannot proceed because the company's wallet
 * does not have enough available funds.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity — the request is syntactically valid
 * and the resource exists, but the domain invariant (balance ≥ cost) is violated.
 *
 * <p>The message intentionally avoids exposing the exact balance or reserved
 * amount; it tells the caller what they need to know: top up required.
 */
public class InsufficientBalanceException extends InterviewEngineException {

    /** Cost of the requested operation in paise. */
    private final long requiredPaise;

    /** Available balance (balance − reserved) in paise. */
    private final long availablePaise;

    public InsufficientBalanceException(long requiredPaise, long availablePaise) {
        super("Insufficient wallet balance. Required: ₹%.2f, Available: ₹%.2f. Please top up your wallet."
                .formatted(requiredPaise / 100.0, availablePaise / 100.0));
        this.requiredPaise = requiredPaise;
        this.availablePaise = availablePaise;
    }

    public long getRequiredPaise() {
        return requiredPaise;
    }

    public long getAvailablePaise() {
        return availablePaise;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    @Override
    public String getErrorCode() {
        return com.interviewengine.shared.dto.ApiErrorResponse.ErrorCode.INSUFFICIENT_BALANCE;
    }
}
