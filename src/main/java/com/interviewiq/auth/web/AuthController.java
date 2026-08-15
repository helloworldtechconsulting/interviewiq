package com.interviewiq.auth.web;

import com.interviewiq.auth.dto.AuthResponse;
import com.interviewiq.auth.dto.ForgotPasswordRequest;
import com.interviewiq.auth.dto.LoginRequest;
import com.interviewiq.auth.dto.LogoutRequest;
import com.interviewiq.auth.dto.RefreshRequest;
import com.interviewiq.auth.dto.RegisterRequest;
import com.interviewiq.auth.dto.ResendVerificationRequest;
import com.interviewiq.auth.dto.ResetPasswordRequest;
import com.interviewiq.auth.dto.VerifyEmailRequest;
import com.interviewiq.auth.service.AuthService;
import com.interviewiq.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.interviewiq.shared.exception.AuthorizationException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints for employer users.
 *
 * <p>All paths are prefixed with {@code /api/v1/{slug}/auth} where {@code slug}
 * identifies the company. This keeps tenant isolation visible in the URL and
 * avoids any cross-tenant lookup ambiguity.
 *
 * <p>These endpoints are permit-all in {@code SecurityConfig} under
 * {@code /api/v1/auth/**}. The slug is part of the path but the pattern
 * match is handled by the security config wildcard.
 */
@RestController
@RequestMapping("/api/v1/{slug}/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    public AuthController(AuthService authService, RefreshTokenCookie refreshTokenCookie) {
        this.authService        = authService;
        this.refreshTokenCookie = refreshTokenCookie;
    }

    /**
     * POST /api/v1/{slug}/auth/register
     * Creates a new user and sends an email-verification OTP.
     * Returns 201 with no body on success.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> register(
            @PathVariable String slug,
            @Valid @RequestBody RegisterRequest request) {
        authService.register(slug, request);
        return ApiResponse.ok();
    }

    /**
     * POST /api/v1/{slug}/auth/verify-email
     * Validates the email-verification OTP and returns a token pair.
     */
    @PostMapping("/verify-email")
    public ApiResponse<AuthResponse> verifyEmail(
            @PathVariable String slug,
            @Valid @RequestBody VerifyEmailRequest request,
            HttpServletResponse httpResponse) {
        return issue(authService.verifyEmail(slug, request), httpResponse);
    }

    /**
     * POST /api/v1/{slug}/auth/resend-verification
     * Resends the email-verification OTP. Always returns 200 (no user enumeration).
     */
    @PostMapping("/resend-verification")
    public ApiResponse<Void> resendVerification(
            @PathVariable String slug,
            @Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(slug, request.email());
        return ApiResponse.ok();
    }

    /**
     * POST /api/v1/{slug}/auth/login
     * Authenticates with email + password and returns a token pair.
     *
     * <p>The client IP is extracted from {@code X-Forwarded-For} (set by ALB/CloudFront)
     * and passed to {@link com.interviewiq.auth.service.AuthService#login} for
     * per-IP failed-login rate limiting.
     */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @PathVariable String slug,
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String clientIp = resolveClientIp(httpRequest);
        return issue(authService.login(slug, request, clientIp), httpResponse);
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }

    /**
     * POST /api/v1/{slug}/auth/refresh
     * Rotates the refresh token and returns a new token pair.
     */
    /**
     * Rotates the refresh token.
     *
     * <p>The incoming token is read from the HTTP-only cookie, never the body
     * (PRD v2.1 §7.1.1) — the SPA cannot read it, which is the entire point, so
     * it could not send one even if it wanted to. The rotated token goes back
     * out the same way.
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            @PathVariable String slug,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String token = refreshTokenCookie.read(httpRequest)
                .orElseThrow(() -> new AuthorizationException("No active session."));

        // Refresh is stateless with respect to slug — company context comes from
        // the stored refresh token's userId. Slug parameter kept for URL symmetry.
        return issue(authService.refresh(new RefreshRequest(token)), httpResponse);
    }

    /**
     * POST /api/v1/{slug}/auth/logout
     * Revokes all refresh tokens for the user. Returns 204.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @PathVariable String slug,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        // Clear the cookie whether or not a token was present: a logout that
        // leaves the cookie behind is worse than one that had nothing to revoke.
        refreshTokenCookie.read(httpRequest)
                .ifPresent(token -> authService.logout(new LogoutRequest(token)));
        refreshTokenCookie.clear(httpResponse);
    }

    /**
     * Sets the refresh cookie and returns the body without the token in it.
     *
     * <p>Every path that mints a token pair goes through here, so there is one
     * place responsible for keeping the refresh token out of the response body.
     */
    private ApiResponse<AuthResponse> issue(AuthResponse.WithRefreshToken issued,
                                            HttpServletResponse httpResponse) {
        refreshTokenCookie.set(httpResponse, issued.refreshToken());
        return ApiResponse.ok(issued.response());
    }

    /**
     * POST /api/v1/{slug}/auth/forgot-password
     * Sends a password-reset OTP. Always returns 200 (no user enumeration).
     */
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(
            @PathVariable String slug,
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(slug, request);
        return ApiResponse.ok();
    }

    /**
     * POST /api/v1/{slug}/auth/reset-password
     * Validates the reset OTP and updates the password.
     */
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(
            @PathVariable String slug,
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(slug, request);
        return ApiResponse.ok();
    }
}
