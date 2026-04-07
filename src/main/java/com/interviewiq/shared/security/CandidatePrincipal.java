package com.interviewiq.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Immutable principal placed in the {@link org.springframework.security.core.context.SecurityContext}
 * after a successful candidate invite-token authentication.
 *
 * <p>Candidates authenticate via a one-time HMAC-SHA256 invite token — not via
 * a username/password or long-lived JWT. The candidate filter chain validates the
 * token and populates this principal, which carries:
 * <ul>
 *   <li>{@code companyId}  — multi-tenant isolation for any candidate-facing endpoint</li>
 *   <li>{@code sessionId}  — the specific session the candidate was invited to</li>
 *   <li>{@code candidateId} — the candidate's own UUID</li>
 * </ul>
 *
 * <p>Candidates are scoped to a single session per authentication — they cannot
 * access other sessions even within the same company.
 *
 * @param companyId   UUID of the company that invited the candidate
 * @param sessionId   UUID of the interview session the candidate is attending
 * @param candidateId UUID of the candidate
 */
public record CandidatePrincipal(
        UUID companyId,
        UUID sessionId,
        UUID candidateId
) implements UserDetails {

    private static final List<GrantedAuthority> CANDIDATE_AUTHORITIES =
            List.of((GrantedAuthority) () -> "ROLE_CANDIDATE");

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return CANDIDATE_AUTHORITIES;
    }

    /** Returns the session ID string as the Spring Security username. */
    @Override
    public String getUsername() {
        return sessionId.toString();
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
