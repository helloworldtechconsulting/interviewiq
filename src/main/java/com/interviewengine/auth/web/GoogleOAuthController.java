package com.interviewengine.auth.web;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.interviewengine.auth.domain.User;
import com.interviewengine.auth.domain.UserRole;
import com.interviewengine.auth.dto.AuthResponse;
import com.interviewengine.auth.dto.GoogleAuthRequest;
import com.interviewengine.auth.dto.GoogleRegisterRequest;
import com.interviewengine.auth.infrastructure.RefreshTokenRepository;
import com.interviewengine.auth.infrastructure.UserRepository;
import com.interviewengine.auth.service.AuthService;
import com.interviewengine.auth.service.GoogleOAuthService;
import com.interviewengine.auth.service.TokenService;
import com.interviewengine.billing.domain.Wallet;
import com.interviewengine.billing.infrastructure.WalletRepository;
import com.interviewengine.company.domain.Company;
import com.interviewengine.company.domain.CompanyStatus;
import com.interviewengine.company.infrastructure.CompanyRepository;
import com.interviewengine.shared.dto.ApiResponse;
import com.interviewengine.shared.exception.AuthorizationException;
import com.interviewengine.shared.exception.ConflictException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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
    private final com.interviewengine.auth.config.SecurityProperties securityProperties;
    private final RefreshTokenCookie  refreshTokenCookie;

    public GoogleOAuthController(GoogleOAuthService googleOAuthService,
                                 AuthService authService,
                                 UserRepository userRepository,
                                 CompanyRepository companyRepository,
                                 WalletRepository walletRepository,
                                 TokenService tokenService,
                                 RefreshTokenRepository refreshTokenRepository,
                                 com.interviewengine.auth.config.SecurityProperties securityProperties,
                                 RefreshTokenCookie refreshTokenCookie) {
        this.googleOAuthService      = googleOAuthService;
        this.authService             = authService;
        this.userRepository          = userRepository;
        this.companyRepository       = companyRepository;
        this.walletRepository        = walletRepository;
        this.tokenService            = tokenService;
        this.refreshTokenRepository  = refreshTokenRepository;
        this.securityProperties      = securityProperties;
        this.refreshTokenCookie      = refreshTokenCookie;
    }

    /**
     * Sets the refresh cookie and returns a body that omits the token.
     * Mirrors AuthController.issue — Google sign-in must not be the one path
     * that leaks a refresh token into JavaScript's reach (PRD v2.1 §7.1.1).
     */
    private ApiResponse<AuthResponse> issue(AuthResponse.WithRefreshToken issued,
                                            jakarta.servlet.http.HttpServletResponse httpResponse) {
        refreshTokenCookie.set(httpResponse, issued.refreshToken());
        return ApiResponse.ok(issued.response());
    }

    @PostMapping("/api/v1/{slug}/auth/google")
    @Transactional
    public ApiResponse<AuthResponse> googleLogin(
            @PathVariable String slug,
            @Valid @RequestBody GoogleAuthRequest request,
            jakarta.servlet.http.HttpServletResponse httpResponse) {

        Payload payload = googleOAuthService.verify(request.idToken());

        String googleSubject = payload.getSubject();
        String email         = payload.getEmail().toLowerCase();
        String fullName      = (String) payload.get("name");
        if (fullName == null || fullName.isBlank()) fullName = email;

        Company company = authService.requireCompanyBySlug(slug);

        User user = userRepository
                .findByCompanyIdAndGoogleSubject(company.getId(), googleSubject)
                .orElse(null);

        if (user == null) {
            user = userRepository
                    .findByCompanyIdAndEmail(company.getId(), email)
                    .orElse(null);

            if (user != null) {
                user.setGoogleSubject(googleSubject);
                user.setEmailVerified(true);
                userRepository.save(user);
                log.info("Google account linked to existing user: userId={}", user.getId());
            }
        }

        if (user == null) {
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

        return issue(issueTokenPair(user), httpResponse);
    }

    @PostMapping("/api/v1/auth/google/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ApiResponse<AuthResponse> googleRegister(
            @Valid @RequestBody GoogleRegisterRequest request,
            jakarta.servlet.http.HttpServletResponse httpResponse) {

        Payload payload = googleOAuthService.verify(request.idToken());

        String googleSubject = payload.getSubject();
        String email         = payload.getEmail().toLowerCase();
        String fullName      = (String) payload.get("name");
        if (fullName == null || fullName.isBlank()) fullName = email;

        String baseSlug = request.companyName().toLowerCase()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9-]", "");
        String slug = resolveUniqueSlug(baseSlug);

        if (companyRepository.existsBySlug(slug)) {
            throw new ConflictException("A company with this slug already exists. Please try a different company name.");
        }

        Company company = new Company();
        company.setName(request.companyName().strip());
        company.setSlug(slug);
        company.setStatus(CompanyStatus.ACTIVE);
        companyRepository.save(company);

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
        admin.setEmailVerified(true);
        userRepository.save(admin);

        Wallet wallet = new Wallet();
        wallet.setCompanyId(company.getId());
        walletRepository.save(wallet);

        log.info("Company registered via Google OAuth: companyId={} slug={} adminEmail={}",
                company.getId(), slug, email);

        AuthResponse.WithRefreshToken issued = issueTokenPair(admin);
        refreshTokenCookie.set(httpResponse, issued.refreshToken());
        return ApiResponse.created(issued.response());
    }

    private AuthResponse.WithRefreshToken issueTokenPair(User user) {
        String accessToken = tokenService.generateAccessToken(user);
        String rawRefresh  = tokenService.generateRefreshToken();

        com.interviewengine.auth.domain.RefreshToken rt = new com.interviewengine.auth.domain.RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(tokenService.hashToken(rawRefresh));
        rt.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                .plus(securityProperties.getJwt().getRefreshTokenExpiration()));
        refreshTokenRepository.save(rt);

        return new AuthResponse.WithRefreshToken(
                new AuthResponse(accessToken, com.interviewengine.auth.dto.UserResponse.from(user)),
                rawRefresh);
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
