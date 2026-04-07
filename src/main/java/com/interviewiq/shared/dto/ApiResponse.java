package com.interviewiq.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Canonical success envelope for all InterviewIQ API responses.
 *
 * <p>Every controller method returns {@code ApiResponse<T>} so clients receive a
 * consistent structure regardless of the domain:
 * <pre>
 * {
 *   "success": true,
 *   "data": { ... }       // present on 200/201
 * }
 * </pre>
 *
 * <p>For operations that produce no payload (e.g. DELETE, status transitions) use
 * {@link #ok()} which omits the {@code data} field entirely via
 * {@link JsonInclude.Include#NON_NULL}.
 *
 * @param <T> the type of the response payload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data
) {

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /** 200 OK with a payload. */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data);
    }

    /** 200/204 with no payload — {@code data} field is omitted from JSON. */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null);
    }

    /** 201 Created — semantically identical to {@link #ok(T)} but named for clarity. */
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, data);
    }
}
