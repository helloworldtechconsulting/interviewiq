package com.interviewengine.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Immutable principal placed in the {@link org.springframework.security.core.context.SecurityContext}
 * after a successful employer JWT authentication.
 *
 * <p>Carries the two IDs that virtually every service-layer check needs:
 * <ul>
 *   <li>{@code companyId} — used for multi-tenant data isolation</li>
 *   <li>{@code userId}    — used for audit logs and per-user rate limiting</li>
 * </ul>
 *
 * <p>Implements {@link UserDetails} so it slots into standard Spring Security
 * infrastructure (e.g. {@code @AuthenticationPrincipal EmployerPrincipal} in
 * controller method signatures) without a separate {@code UsernamePasswordAuthenticationToken}
 * wrapper carrying a different type.
 *
 * @param companyId UUID of the company the employer belongs to
 * @param userId    UUID of the authenticated user
 * @param email     the employer's email address (username for Spring Security)
 * @param roles     granted roles (e.g. {@code ROLE_EMPLOYER}, {@code ROLE_ADMIN})
 */
public record EmployerPrincipal(
        UUID companyId,
        UUID userId,
        String email,
        List<GrantedAuthority> roles
) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles;
    }

    /** Returns the email — Spring Security uses this as the username. */
    @Override
    public String getUsername() {
        return email;
    }

    /**
     * Password is managed by the auth module and never stored on the principal.
     * Returns an empty string; {@code isCredentialsNonExpired} covers expiry.
     */
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
