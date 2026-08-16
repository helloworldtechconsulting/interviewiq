package com.interviewengine.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by the Bucket4j rate-limiting layer when a caller exceeds the
 * configured request quota for a given endpoint or IP address.
 *
 * <p>Maps to HTTP 429 Too Many Requests.
 *
 * <p>The {@code retryAfterSeconds} field allows the caller to know how long
 * to wait before retrying. The {@link com.interviewengine.shared.web.GlobalExceptionHandler}
 * writes this value into the {@code Retry-After} response header.
 */
public class RateLimitException extends InterviewEngineException {

    /** Seconds until the rate-limit bucket refills. May be 0 if unknown. */
    private final long retryAfterSeconds;

    public RateLimitException(long retryAfterSeconds) {
        super("Rate limit exceeded. Retry after %d second(s).".formatted(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public RateLimitException() {
        this(0L);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }

    @Override
    public String getErrorCode() {
        return com.interviewengine.shared.dto.ApiErrorResponse.ErrorCode.RATE_LIMIT_EXCEEDED;
    }
}
