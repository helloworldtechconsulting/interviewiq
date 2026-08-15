package com.interviewiq.auth.service;

import com.interviewiq.auth.config.SecurityProperties;
import com.interviewiq.auth.domain.OtpPurpose;
import com.interviewiq.auth.domain.RefreshToken;
import com.interviewiq.auth.domain.User;
import com.interviewiq.auth.domain.UserRole;
import com.interviewiq.auth.dto.AuthResponse;
import com.interviewiq.auth.dto.ForgotPasswordRequest;
import com.interviewiq.auth.dto.LoginRequest;
import com.interviewiq.auth.dto.LogoutRequest;
import com.interviewiq.auth.dto.RefreshRequest;
import com.interviewiq.auth.dto.RegisterRequest;
import com.interviewiq.auth.dto.ResetPasswordRequest;
import com.interviewiq.auth.dto.UserResponse;
import com.interviewiq.auth.dto.VerifyEmailRequest;
import com.interviewiq.auth.infrastructure.RefreshTokenRepository;
import com.interviewiq.auth.infrastructure.UserRepository;
import com.interviewiq.company.domain.Company;
import com.interviewiq.billing.service.PromotionalGrantService;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.shared.exception.AuthorizationException;
import com.interviewiq.shared.exception.ConflictException;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Core authentication service for employer users.
 *
 * <p>All login queries MUST scope to {@code companyId}. The same email
 * can exist across multiple companies — never query users by email alone.
 *
 * <p>Company is resolved from the URL path variable {@code slug}, so every
 * public endpoint that needs a company passes the slug in. The AuthController
 * resolves it here via {@link #requireCompanyBySlug(String)}.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository         userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CompanyRepository      companyRepository;
    private final OtpService             otpService;
    private final TokenService           tokenService;
    private final PasswordEncoder        passwordEncoder;
    private final SecurityProperties     securityProperties;
    private final LoginAttemptService    loginAttemptService;
    private final PromotionalGrantService promotionalGrantService;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       CompanyRepository companyRepository,
                       OtpService otpService,
                       TokenService tokenService,
                       PasswordEncoder passwordEncoder,
                       SecurityProperties securityProperties,
                       LoginAttemptService loginAttemptService,
                       PromotionalGrantService promotionalGrantService) {
        this.userRepository        = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.companyRepository     = companyRepository;
        this.otpService            = otpService;
        this.tokenService          = tokenService;
        this.passwordEncoder       = passwordEncoder;
        this.securityProperties    = securityProperties;
        this.loginAttemptService   = loginAttemptService;
        this.promotionalGrantService = promotionalGrantService;
    }

    // =========================================================================
    // Registration
    // =========================================================================

    /**
     * Creates a new user within an existing company and immediately sends the
     * email-verification OTP. The account is blocked from login until
     * {@link #verifyEmail} is called successfully.
     *
     * @param slug    company URL slug to register under
     * @param request registration payload
     * @throws ConflictException if the email already exists in this company
     */
    @Transactional
    public void register(String slug, RegisterRequest request) {
        Company company = requireCompanyBySlug(slug);
        String email = request.email().toLowerCase();

        if (userRepository.existsByCompanyIdAndEmail(company.getId(), email)) {
            throw new ConflictException("An account with this email already exists.");
        }

        User user = new User();
        user.setCompanyId(company.getId());
        user.setFullName(request.fullName().strip());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.RECRUITER);
        user.setActive(true);
        user.setEmailVerified(false);
        userRepository.save(user);

        otpService.sendOtp(email, OtpPurpose.EMAIL_VERIFICATION, company.getId(), user.getId());
        log.info("User registered: companyId={} email={}", company.getId(), email);
    }

    // =========================================================================
    // Email verification
    // =========================================================================

    /**
     * Verifies the email-verification OTP and activates the account, returning
     * a full token pair so the user is immediately logged in.
     */
    @Transactional
    public AuthResponse verifyEmail(String slug, VerifyEmailRequest request) {
        Company company = requireCompanyBySlug(slug);
        String email = request.email().toLowerCase();

        User user = userRepository.findByCompanyIdAndEmail(company.getId(), email)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found."));

        if (user.isEmailVerified()) {
            throw new ValidationException("Email is already verified.");
        }

        otpService.verifyOtp(email, request.otp(), OtpPurpose.EMAIL_VERIFICATION);

        user.setEmailVerified(true);
        userRepository.save(user);

        // PRD v2.1 §7.1.1: "On successful verification the signup promotional
        // grant is applied." Failure to grant must not fail verification — the
        // user has proved their address and is entitled to their account either
        // way, and a missing grant is a support question, not a broken signup.
        try {
            promotionalGrantService.applySignupGrantIfEligible(company.getId(), email);
        } catch (Exception e) {
            log.error("Signup promotional grant failed for companyId={}: {}",
                    company.getId(), e.getMessage(), e);
        }

        log.info("Email verified: userId={}", user.getId());
        return issueTokenPair(user);
    }

    /**
     * Resends the email-verification OTP. Silently succeeds even if the email
     * is not found (prevents user enumeration).
     */
    @Transactional
    public void resendVerification(String slug, String email) {
        Company company = requireCompanyBySlug(slug);
        String emailLower = email.toLowerCase();

        userRepository.findByCompanyIdAndEmail(company.getId(), emailLower)
                .filter(u -> !u.isEmailVerified())
                .ifPresent(u -> otpService.sendOtp(
                        emailLower, OtpPurpose.EMAIL_VERIFICATION, company.getId(), u.getId()));
    }

    // =========================================================================
    // Login / Refresh / Logout
    // =========================================================================

    /**
     * Authenticates with email + password and returns a token pair.
     *
     * <p>Enforces per-IP failed-login rate limiting via {@link LoginAttemptService}:
     * 5 failures within 1 minute triggers a 15-minute lockout for the source IP.
     *
     * @param slug      company URL slug
     * @param request   login credentials
     * @param clientIp  the client's IP address (extracted by the controller from
     *                  {@code X-Forwarded-For} or {@code RemoteAddr})
     * @throws com.interviewiq.shared.exception.RateLimitException if the IP is locked out (HTTP 429)
     * @throws AuthorizationException for any credential failure (no user enumeration)
     */
    @Transactional
    public AuthResponse login(String slug, LoginRequest request, String clientIp) {
        loginAttemptService.checkLockout(clientIp);

        Company company = requireCompanyBySlug(slug);
        String email = request.email().toLowerCase();

        User user = userRepository
                .findByCompanyIdAndEmailAndEmailVerifiedTrue(company.getId(), email)
                .orElse(null);

        if (user == null || !user.isActive() || user.getPasswordHash() == null ||
                !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(clientIp);
            throw new AuthorizationException("Invalid credentials.");
        }

        loginAttemptService.resetFailures(clientIp);

        user.setLastLoginAt(OffsetDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);

        log.info("User logged in: userId={}", user.getId());
        return issueTokenPair(user);
    }

    /**
     * Rotates a refresh token: validates the submitted token, revokes it, and
     * issues a new token pair.
     */
    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String hash = tokenService.hashToken(request.refreshToken());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new AuthorizationException("Invalid or expired refresh token."));

        if (stored.isRevoked()) {
            throw new AuthorizationException("Refresh token has been revoked.");
        }
        if (stored.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new AuthorizationException("Refresh token has expired.");
        }

        // Revoke the consumed token
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        if (!user.isActive()) {
            throw new AuthorizationException("Account is disabled.");
        }

        return issueTokenPair(user);
    }

    /**
     * Revokes all active refresh tokens for the authenticated user.
     */
    @Transactional
    public void logout(LogoutRequest request) {
        String hash = tokenService.hashToken(request.refreshToken());
        refreshTokenRepository.findByTokenHash(hash)
                .ifPresent(rt -> refreshTokenRepository.revokeAllByUserId(rt.getUserId()));
    }

    // =========================================================================
    // Password reset
    // =========================================================================

    /**
     * Sends a password-reset OTP. Silently succeeds when email is unknown
     * (prevents user enumeration).
     */
    @Transactional
    public void forgotPassword(String slug, ForgotPasswordRequest request) {
        Company company = requireCompanyBySlug(slug);
        String email = request.email().toLowerCase();

        userRepository.findByCompanyIdAndEmailAndEmailVerifiedTrue(company.getId(), email)
                .filter(User::isActive)
                .ifPresent(u -> otpService.sendOtp(
                        email, OtpPurpose.PASSWORD_RESET, company.getId(), u.getId()));
    }

    /**
     * Validates the password-reset OTP and updates the password.
     */
    @Transactional
    public void resetPassword(String slug, ResetPasswordRequest request) {
        Company company = requireCompanyBySlug(slug);
        String email = request.email().toLowerCase();

        User user = userRepository
                .findByCompanyIdAndEmailAndEmailVerifiedTrue(company.getId(), email)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found."));

        otpService.verifyOtp(email, request.otp(), OtpPurpose.PASSWORD_RESET);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Invalidate all existing sessions after a password change
        refreshTokenRepository.revokeAllByUserId(user.getId());
        log.info("Password reset: userId={}", user.getId());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Resolves a company by slug or throws 404. */
    public Company requireCompanyBySlug(String slug) {
        return companyRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found."));
    }

    /**
     * Generates a new RS256 access token and a rotating refresh token, persists
     * the refresh token hash, and returns both in an {@link AuthResponse}.
     */
    private AuthResponse issueTokenPair(User user) {
        String accessToken  = tokenService.generateAccessToken(user);
        String rawRefresh   = tokenService.generateRefreshToken();

        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(tokenService.hashToken(rawRefresh));
        rt.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                .plus(securityProperties.getJwt().getRefreshTokenExpiration()));
        refreshTokenRepository.save(rt);

        return new AuthResponse(accessToken, rawRefresh, UserResponse.from(user));
    }
}
