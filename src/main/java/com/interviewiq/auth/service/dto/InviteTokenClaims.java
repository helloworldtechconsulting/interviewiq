package com.interviewiq.auth.service.dto;

import java.util.UUID;

/**
 * Decoded, validated claims from a verified HS256 candidate invite token.
 *
 * <p>Constructed by {@link com.interviewiq.auth.service.TokenService#validateInviteToken(String)}
 * after signature verification and expiry check succeed.  The candidate filter uses
 * these claims to build the {@link com.interviewiq.shared.security.CandidatePrincipal}.
 *
 * @param sessionId   the interview session the candidate is invited to (JWT {@code sub} claim)
 * @param candidateId the candidate's UUID (JWT {@code canId} claim)
 * @param companyId   the company that issued the invitation (JWT {@code cid} claim)
 */
public record InviteTokenClaims(
        UUID sessionId,
        UUID candidateId,
        UUID companyId
) {}
