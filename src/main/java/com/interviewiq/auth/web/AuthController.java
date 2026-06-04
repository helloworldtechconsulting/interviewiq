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

    public AuthController(AuthService authService) {
        this.authService = authService;
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
            @Valid @RequestBody VerifyEmailRequest request) {
        return ApiResponse.ok(authService.verifyEmail(slug, request));
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
            HttpServletRequest httpRequest) {
        String clientIp = resolveClientIp(httpRequest);
        return ApiResponse.ok(authService.login(slug, request, clientIp));
    }

    // ── Private helper ────────────────────────────────────────────────────────

    /**
     * Resolves the actual client IP, preferring the first value of
     * {@code X-Forwarded-For} (set by AWS ALB / CloudFront) over the
     * raw remote address.
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For may contain a comma-separated list; take the first
            return xff.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }

    /**
     * POST /api/v1/{slug}/auth/refresh
     * Rotates the refresh token and returns a new token pair.
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            @PathVariable String slug,
            @Valid @RequestBody RefreshRequest request) {
        // Refresh is stateless with respect to slug — company context comes from
        // the stored refresh token's userId. Slug parameter kept for URL symmetry.
        return ApiResponse.ok(authService.refresh(request));
    }

    /**
     * POST /api/v1/{slug}/auth/logout
     * Revokes all refresh tokens for the user. Returns 204.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @PathVariable String slug,
            @Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
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
