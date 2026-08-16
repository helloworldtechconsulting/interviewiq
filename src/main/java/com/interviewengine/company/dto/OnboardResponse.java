package com.interviewengine.company.dto;

/**
 * Response body returned after a successful company onboarding.
 *
 * <p>No tokens are issued yet — the admin user must verify their email first.
 * The {@code slug} is returned so the frontend can redirect to the
 * {@code /api/v1/{slug}/auth/verify-email} endpoint.
 *
 * @param slug  the company slug — used to construct subsequent auth URLs
 * @param email the admin email address that will receive the verification OTP
 */
public record OnboardResponse(
        String slug,
        String email
) {}
