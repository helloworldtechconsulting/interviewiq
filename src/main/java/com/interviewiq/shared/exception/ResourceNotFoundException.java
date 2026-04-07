package com.interviewiq.shared.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown when a requested resource does not exist or is not visible to the caller.
 *
 * <p>Maps to HTTP 404 Not Found.
 *
 * <p>Usage examples:
 * <pre>
 * throw new ResourceNotFoundException("InterviewSession", sessionId);
 * throw new ResourceNotFoundException("JobOpening", jobOpeningId);
 * </pre>
 */
public class ResourceNotFoundException extends InterviewIqException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    /**
     * Convenience constructor that formats a standard not-found message.
     *
     * @param resourceType human-readable resource type (e.g. "InterviewSession")
     * @param id           the UUID that was not found
     */
    public ResourceNotFoundException(String resourceType, UUID id) {
        super(resourceType + " not found: " + id);
    }

    /**
     * Convenience constructor for string-keyed resources (e.g. slug, token).
     *
     * @param resourceType human-readable resource type
     * @param key          the key value that was not found
     */
    public ResourceNotFoundException(String resourceType, String key) {
        super(resourceType + " not found: " + key);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public String getErrorCode() {
        return com.interviewiq.shared.dto.ApiErrorResponse.ErrorCode.RESOURCE_NOT_FOUND;
    }
}
