package com.interviewiq.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code PATCH /api/v1/candidate/session/meet-url}.
 *
 * <p>Validates that the provided URL is non-blank and matches the Google Meet
 * URL pattern before it reaches the service layer. This replaces the previous
 * {@code Map<String, String>} body, which silently passed {@code null} to the
 * service when the {@code "googleMeetUrl"} key was absent or misspelled.
 */
public record SetMeetUrlRequest(
        @NotBlank(message = "googleMeetUrl must not be blank")
        @Pattern(
                regexp = "https://meet\\.google\\.com/.+",
                message = "googleMeetUrl must be a valid Google Meet URL (https://meet.google.com/...)"
        )
        String googleMeetUrl
) {}
