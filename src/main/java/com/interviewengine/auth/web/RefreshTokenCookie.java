package com.interviewengine.auth.web;

import com.interviewengine.auth.config.SecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/**
 * Carries the refresh token in an HTTP-only cookie (PRD v2.1 §7.1.1).
 *
 * <p>The PRD states the requirement in bold and repeats it in the risk register:
 * "<strong>The refresh token must never be stored in</strong> {@code localStorage}",
 * and §17 lists "XSS becomes full account takeover" as HIGH severity with the
 * mitigation "refresh token in an HTTP-only cookie, never localStorage".
 *
 * <p>The distinction is the whole point. An access token in memory dies with the
 * tab and lasts 60 minutes; a refresh token in {@code localStorage} is readable
 * by any script on the page and is good for seven days. One XSS payload turns
 * the second into a week of access from the attacker's own machine — which is
 * why the token now lives somewhere JavaScript cannot reach at all.
 *
 * <h2>The four attributes, and why each is set</h2>
 *
 * <ul>
 *   <li>{@code HttpOnly} — the actual mitigation. Script cannot read it.</li>
 *   <li>{@code Secure} — never sent over plaintext. Cloudflare terminates TLS
 *       and re-encrypts to origin, so this holds end to end.</li>
 *   <li>{@code SameSite=None} — required, because the SPA is served from
 *       {@code app.interviewengine.ai} and calls {@code api.interviewengine.ai}. Those
 *       are cross-site for cookie purposes, and {@code Lax} would silently drop
 *       the cookie on the refresh call. This is also why the CORS policy must
 *       enumerate origins rather than use a wildcard (§7.1.3) — credentialed
 *       requests forbid the combination.</li>
 *   <li>{@code Path=/api} — narrows what is sent where.</li>
 * </ul>
 */
@Component
public class RefreshTokenCookie {

    public static final String COOKIE_NAME = "iiq_refresh";

    /** Scoped to the API. Nothing outside it needs to see this cookie. */
    private static final String COOKIE_PATH = "/api";

    private final SecurityProperties securityProperties;

    public RefreshTokenCookie(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    /** Issues the cookie on login, verification and rotation. */
    public void set(HttpServletResponse response, String refreshToken) {
        long maxAgeSeconds = securityProperties.getJwt().getRefreshTokenExpiration().toSeconds();
        response.addHeader("Set-Cookie", build(refreshToken, maxAgeSeconds));
    }

    /**
     * Clears the cookie on logout.
     *
     * <p>Sent with the same attributes as the original. A browser treats a
     * clearing cookie whose Path or SameSite differs as a <em>different</em>
     * cookie, leaves the original in place, and the user stays logged in.
     */
    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", build("", 0));
    }

    /** Reads the refresh token from the request, if present. */
    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /**
     * Built by hand rather than with {@link Cookie}, because the servlet
     * {@code Cookie} API has no SameSite setter — and SameSite is not optional
     * here: without {@code None} the cookie is dropped on every cross-site
     * refresh call and users are logged out after their access token expires.
     */
    private String build(String value, long maxAgeSeconds) {
        return String.join("; ",
                COOKIE_NAME + "=" + value,
                "Path=" + COOKIE_PATH,
                "Max-Age=" + maxAgeSeconds,
                "HttpOnly",
                "Secure",
                "SameSite=None");
    }
}
