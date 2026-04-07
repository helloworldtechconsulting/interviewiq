package com.interviewiq.auth.service.dto;

import com.interviewiq.auth.domain.UserRole;

import java.util.UUID;

/**
 * Decoded, validated claims from a verified RS256 access token.
 *
 * <p>Constructed by {@link com.interviewiq.auth.service.TokenService#validateAccessToken(String)}
 * after signature verification and expiry check succeed.  The filter then uses
 * these claims to build the {@link com.interviewiq.shared.security.EmployerPrincipal}
 * without an additional database round-trip.
 *
 * @param userId    the authenticated user's UUID (JWT {@code sub} claim)
 * @param companyId the user's company UUID (JWT {@code cid} claim)
 * @param email     the user's email address (JWT {@code email} claim)
 * @param role      the user's role (JWT {@code role} claim)
 */
public record AccessTokenClaims(
        UUID userId,
        UUID companyId,
        String email,
        UserRole role
) {}
