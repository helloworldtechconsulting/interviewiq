package com.interviewengine.shared.security;

import com.interviewengine.shared.exception.AuthorizationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Static utility for reading the currently authenticated principal from the
 * Spring Security context.
 *
 * <p>Intended for use in service-layer classes that need to know who is making
 * the request without receiving the principal as a method argument. Controllers
 * should prefer {@code @AuthenticationPrincipal} where possible; services use
 * this utility.
 *
 * <p>The two authentication types are:
 * <ul>
 *   <li>{@link EmployerPrincipal} — authenticated employer user (JWT from the
 *       employer filter chain), carries {@code companyId} and {@code userId}</li>
 *   <li>{@link CandidatePrincipal} — authenticated candidate (invite-token from
 *       the candidate filter chain), carries {@code companyId} and {@code sessionId}</li>
 * </ul>
 *
 * <p>All methods throw {@link AuthorizationException} (HTTP 403) rather than
 * {@link com.interviewengine.shared.exception.ResourceNotFoundException} (HTTP 404)
 * when called without a principal, to avoid disclosing resource existence to
 * unauthenticated callers.
 */
public final class SecurityContext {

    private SecurityContext() {}

    // =========================================================================
    // Employer principal accessors
    // =========================================================================

    /**
     * Returns the {@link EmployerPrincipal} for the current request.
     *
     * @throws AuthorizationException if no authenticated employer is present
     */
    public static EmployerPrincipal requireEmployer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof EmployerPrincipal ep)) {
            throw AuthorizationException.accessDenied();
        }
        return ep;
    }

    /**
     * Returns the authenticated employer's company UUID.
     *
     * @throws AuthorizationException if no authenticated employer is present
     */
    public static UUID requireCompanyId() {
        return requireEmployer().companyId();
    }

    /**
     * Returns the authenticated employer's user UUID.
     *
     * @throws AuthorizationException if no authenticated employer is present
     */
    public static UUID requireUserId() {
        return requireEmployer().userId();
    }

    // =========================================================================
    // Candidate principal accessors
    // =========================================================================

    /**
     * Returns the {@link CandidatePrincipal} for the current request.
     *
     * @throws AuthorizationException if no authenticated candidate is present
     */
    public static CandidatePrincipal requireCandidate() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CandidatePrincipal cp)) {
            throw AuthorizationException.accessDenied();
        }
        return cp;
    }

    // =========================================================================
    // Cross-tenant ownership guard
    // =========================================================================

    /**
     * Enforces that the resource's {@code ownerCompanyId} matches the caller's
     * company.  Throws {@link AuthorizationException} if not — never 404, to
     * avoid disclosing resource existence.
     *
     * <p>Usage example in a service method:
     * <pre>
     * JobOpening jo = jobOpeningRepository.findById(id).orElseThrow(...);
     * SecurityContext.requireSameCompany(jo.getCompanyId());
     * </pre>
     *
     * @param ownerCompanyId the company UUID recorded on the resource being accessed
     * @throws AuthorizationException if the resource belongs to a different company
     */
    public static void requireSameCompany(UUID ownerCompanyId) {
        UUID callerCompanyId = requireCompanyId();
        if (!callerCompanyId.equals(ownerCompanyId)) {
            throw AuthorizationException.accessDenied();
        }
    }

    // =========================================================================
    // Nullable accessors — for code paths that support both principal types
    // =========================================================================

    /**
     * Returns the {@link EmployerPrincipal} or {@code null} if the current
     * authentication is not an employer.  Prefer {@link #requireEmployer()} in
     * employer-only paths.
     */
    public static EmployerPrincipal employerOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof EmployerPrincipal ep) {
            return ep;
        }
        return null;
    }
}
