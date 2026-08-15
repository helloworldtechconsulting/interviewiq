package com.interviewiq.auth.dto;

import com.interviewiq.auth.domain.User;
import com.interviewiq.auth.domain.UserRole;
import com.interviewiq.company.domain.Company;
import com.interviewiq.company.domain.CompanyStatus;

import java.util.UUID;

/**
 * Everything the SPA needs to render its shell for the signed-in user, in one
 * request (PRD v2.1 §11, {@code GET /auth/me}).
 *
 * <h2>Why this exists when the JWT already carries most of it</h2>
 *
 * <p>The access token carries {@code userId}, {@code companyId} and roles, and
 * the client could decode them itself. It should not, for two reasons.
 *
 * <p>First, the token is a <em>snapshot taken at login</em>. An access token
 * lives for the whole of its TTL, so a user whose role is downgraded, whose
 * account is deactivated, or whose company is suspended keeps rendering the old
 * shell until the token happens to refresh. Reading the current state from the
 * database on each page load closes that window. Note the security boundary is
 * unchanged either way — authorisation is still enforced server-side on every
 * endpoint; this only stops the UI from showing a user controls that will fail
 * when clicked.
 *
 * <p>Second, a client that parses JWT claims is a client coupled to the token
 * format. Changing a claim name then becomes a breaking change across two
 * codebases.
 *
 * @param companyStatus present so the UI can distinguish "your company is
 *        suspended" from a generic authorisation failure — the two need very
 *        different messages, and only one of them is the user's problem
 */
public record MeResponse(
        UUID          userId,
        String        fullName,
        String        email,
        UserRole      role,
        boolean       emailVerified,
        UUID          companyId,
        String        companyName,
        String        companySlug,
        CompanyStatus companyStatus
) {

    public static MeResponse of(User user, Company company) {
        return new MeResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.isEmailVerified(),
                company.getId(),
                company.getName(),
                company.getSlug(),
                company.getStatus()
        );
    }
}
