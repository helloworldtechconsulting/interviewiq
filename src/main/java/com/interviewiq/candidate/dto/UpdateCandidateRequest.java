package com.interviewiq.candidate.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Partial-update request for a candidate record.
 *
 * <p>Only non-null fields are applied. {@code jobOpeningId} is deliberately absent:
 * moving a candidate between openings would silently invalidate any generated
 * question set, which is built from the opening's job description. Deleting and
 * re-adding is the honest way to do that.
 *
 * @param fullName updated display name; null means "do not change"
 * @param email    updated email; null = no change. Re-checked for uniqueness within the opening.
 * @param phone    updated phone; null = no change; empty = clear
 */
public record UpdateCandidateRequest(

        @Size(max = 255, message = "Name must be at most 255 characters.")
        String fullName,

        @Email(message = "Enter a valid email address.")
        @Size(max = 255, message = "Email must be at most 255 characters.")
        String email,

        @Size(max = 30, message = "Phone must be at most 30 characters.")
        String phone
) {}
