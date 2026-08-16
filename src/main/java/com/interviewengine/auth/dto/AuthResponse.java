package com.interviewengine.auth.dto;

/**
 * Token pair returned to the client.
 *
 * <p><strong>The refresh token is deliberately absent from this body.</strong>
 * PRD v2.1 §7.1.1 requires it to travel in an HTTP-only cookie and never reach
 * {@code localStorage}; returning it here would hand it straight to JavaScript
 * and undo the protection regardless of where the client then chose to put it.
 *
 * <p>{@code accessToken} stays in the body on purpose. It is short-lived (60
 * minutes), the SPA holds it in memory only, and it has to be readable to be
 * attached as a bearer header.
 *
 * @see com.interviewengine.auth.web.RefreshTokenCookie
 */
public record AuthResponse(
        String       accessToken,
        UserResponse user
) {

    /** Internal carrier so the controller can set the cookie the body omits. */
    public record WithRefreshToken(AuthResponse response, String refreshToken) {}
}
