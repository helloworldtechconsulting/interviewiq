package com.interviewiq.auth.web;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.interviewiq.auth.domain.User;
import com.interviewiq.auth.domain.UserRole;
import com.interviewiq.auth.dto.AuthResponse;
import com.interviewiq.auth.dto.GoogleAuthRequest;
import com.interviewiq.auth.dto.GoogleRegisterRequest;
import com.interviewiq.auth.infrastructure.RefreshTokenRepository;
import com.interviewiq.auth.infrastructure.UserRepository;
import com.interviewiq.auth.service.AuthService;
import com.interviewiq.auth.service.GoogleOAuthService;
import com.interviewiq.auth.service.TokenService;
import com.interviewiq.billing.domain.Wallet;
import com.interviewiq.billing.infrastructure.WalletRepository;
import com.interviewiq.company.domain.Company;
import com.interviewiq.company.domain.CompanyStatus;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.shared.dto.ApiResponse;
import com.interviewiq.shared.exception.AuthorizationException;
import com.interviewiq.shared.exception.ConflictException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Google OAuth endpoints for employer (recruiter/admin) accounts.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/v1/{slug}/auth/google} — login or link an existing account
 *       within a known company. Company-scoped: the same Google account may exist
 *       across different companies without conflict.</li>
 *   <li>{@code POST /api/v1/auth/google/register} — register a brand-new company
 *       with Google OAuth. No slug required (company doesn't exist yet).</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>Both endpoints are permit-all in {@link com.interviewiq.auth.config.SecurityConfig}.
 * The slug-scoped login endpoint is covered by the wildcard pattern for auth routes.
 * The register endpoint is covered by an explicit pattern for Google auth routes.
 *
 * <h2>Google account linking</h2>
 * <p>For the login endpoint: if a user with the matching email already exists in the
 * company but has no {@code googleSubject}, this controller links the Google subject
 * to that account (one-time automatic account linking). If neither email nor
 * {@code googleSubject} matches, a new RECRUITER account is created with
 * {@code emailVerified = true} (Google has already verified it).
 */
@RestController
public class GoogleOAuthController {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthController.class);

    private final GoogleOAuthService  googleOAuthService;
    private final AuthService         authService;
    private final UserRepository      userRepository;
    private final CompanyRepository   companyRepository;
    private final WalletRepository    walletRepository;
    private final TokenService        tokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final com.interviewiq.auth.config.SecurityProperties securityProperties;

    public GoogleOAuthController(GoogleOAuthService googleOAuthService,
                                 AuthService authService,
                                 UserRepository userRepository,
                                 CompanyRepository companyRepository,
                                 WalletRepository walletRepository,
                                 TokenService tokenService,
                                 RefreshTokenRepository refreshTokenRepository,
                                 com.interviewiq.auth.config.SecurityProperties securityProperties) {
        this.googleOAuthService      = googleOAuthService;
        this.authService             = authService;
        this.userRepository          = userRepository;
        this.companyRepository       = companyRepository;
        this.walletRepository        = walletRepository;
        this.tokenService            = tokenService;
        this.refreshTokenRepository  = refreshTokenRepository;
        this.securityProperties      = securityProperties;
    }

    // =========================================================================
    // POST /api/v1/{slug}/auth/google — login / auto-register within company
    // =========================================================================

    /**
     * Logs in or auto-registers an employer using a Google ID token.
     *
     * <p>Covered by the slug-wildcard auth permit-all pattern already in
     * {@code SecurityConfig}.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Verify the Google ID token.</li>
     *   <li>Look up user by {@code (companyId, googleSubject)} — fastest path.</li>
     *   <li>If not found, look up by {@code (companyId, email)} — link google subject.</li>
     *   <li>If still not found, auto-create a RECRUITER account (emailVerified = true).</li>
     *   <li>Issue a JWT + refresh token pair.</li>
     * </ol>
     */
    @PostMapping("/api/v1/{slug}/auth/google")
    @Transactional
    public ApiResponse<AuthResponse> googleLogin(
            @PathVariable String slug,
            @Valid @RequestBody GoogleAuthRequest request) {

        Payload payload = googleOAuthService.verify(request.idToken());

        String googleSubject = payload.getSubject();
        String email         = payload.getEmail().toLowerCase();
        String fullName      = (String) payload.get("name");
        if (fullName == null || fullName.isBlank()) fullName = email;

        Company company = authService.requireCompanyBySlug(slug);

        // 1. Lookup by google subject (already linked)
        User user = userRepository
                .findByCompanyIdAndGoogleSubject(company.getId(), googleSubject)
                .orElse(null);

        if (user == null) {
            // 2. Lookup by email — auto-link
            user = userRepository
                    .findByCompanyIdAndEmail(company.getId(), email)
                    .orElse(null);

            if (user != null) {
                // Link the Google subject to the existing account
                user.setGoogleSubject(googleSubject);
                user.setEmailVerified(true); // Google verifies email
                userRepository.save(user);
                log.info("Google account linked to existing user: userId={}", user.getId());
            }
        }

        if (user == null) {
            // 3. Auto-create a RECRUITER account (Google email is already verified)
            user = new User();
            user.setCompanyId(company.getId());
            user.setFullName(fullName.strip());
            user.setEmail(email);
            user.setGoogleSubject(googleSubject);
            user.setRole(UserRole.RECRUITER);
            user.setActive(true);
            user.setEmailVerified(true);
            userRepository.save(user);
            log.info("Auto-created user via Google OAuth: companyId={} email={}", company.getId(), email);
        }

        if (!user.isActive()) {
            throw new AuthorizationException("Account is disabled.");
        }

        user.setLastLoginAt(OffsetDateTime.now(ZoneOffset.UTC));
        userRepository.save(user);

        return ApiResponse.ok(issueTokenPair(user));
    }

    // =========================================================================
    // POST /api/v1/auth/google/register — register a brand-new company
    // =========================================================================

    /**
     * Registers a brand-new company using a Google ID token.
     *
     * <p>This endpoint does NOT require a slug — the company doesn't exist yet.
     * A slug is derived from the {@code companyName}.
     *
     * <p>The created admin account is immediately {@code emailVerified = true}
     * because Google has already verified the email.
     *
     * <p>No OTP email is dispatched (email is already verified via Google).
     */
    @PostMapping("/api/v1/auth/google/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ApiResponse<AuthResponse> googleRegister(
            @Valid @RequestBody GoogleRegisterRequest request) {

        Payload payload = googleOAuthService.verify(request.idToken());

        String googleSubject = payload.getSubject();
        String email         = payload.getEmail().toLowerCase();
        String fullName      = (String) payload.get("name");
        if (fullName == null || fullName.isBlank()) fullName = email;

        // Derive slug from company name
        String baseSlug = request.companyName().toLowerCase()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9-]", "");
        String slug = resolveUniqueSlug(baseSlug);

        if (companyRepository.existsBySlug(slug)) {
            throw new ConflictException("A company with this slug already exists. Please try a different company name.");
        }

        // ── Create Company ─────────────────────────────────────────────────────
        Company company = new Company();
        company.setName(request.companyName().strip());
        company.setSlug(slug);
        company.setStatus(CompanyStatus.ACTIVE);
        companyRepository.save(company);

        // ── Create admin User ──────────────────────────────────────────────────
        if (userRepository.existsByCompanyIdAndEmail(company.getId(), email)) {
            throw new ConflictException("An account with this email already exists.");
        }

        User admin = new User();
        admin.setCompanyId(company.getId());
        admin.setFullName(fullName.strip());
        admin.setEmail(email);
        admin.setGoogleSubject(googleSubject);
        admin.setRole(UserRole.ADMIN);
        admin.setActive(true);
        admin.setEmailVerified(true); // Google already verified the email
        userRepository.save(admin);

        // ── Create empty Wallet ────────────────────────────────────────────────
        Wallet wallet = new Wallet();
        wallet.setCompanyId(company.getId());
        walletRepository.save(wallet);

        log.info("Company registered via Google OAuth: companyId={} slug={} adminEmail={}",
                company.getId(), slug, email);

        return ApiResponse.created(issueTokenPair(admin));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private AuthResponse issueTokenPair(User user) {
        String accessToken = tokenService.generateAccessToken(user);
        String rawRefresh  = tokenService.generateRefreshToken();

        com.interviewiq.auth.domain.RefreshToken rt = new com.interviewiq.auth.domain.RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(tokenService.hashToken(rawRefresh));
        rt.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                .plus(securityProperties.getJwt().getRefreshTokenExpiration()));
        refreshTokenRepository.save(rt);

        return new AuthResponse(accessToken, rawRefresh,
                com.interviewiq.auth.dto.UserResponse.from(user));
    }

    private String resolveUniqueSlug(String base) {
        if (base.isBlank()) base = "company";
        if (!companyRepository.existsBySlug(base)) return base;
        int suffix = 2;
        while (companyRepository.existsBySlug(base + "-" + suffix)) {
            suffix++;
        }
        return base + "-" + suffix;
    }
}
