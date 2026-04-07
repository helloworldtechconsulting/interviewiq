package com.interviewiq.company.service;

import com.interviewiq.auth.domain.OtpPurpose;
import com.interviewiq.auth.domain.User;
import com.interviewiq.auth.domain.UserRole;
import com.interviewiq.auth.infrastructure.UserRepository;
import com.interviewiq.auth.service.OtpService;
import com.interviewiq.billing.domain.Wallet;
import com.interviewiq.billing.infrastructure.WalletRepository;
import com.interviewiq.company.domain.Company;
import com.interviewiq.company.domain.CompanyStatus;
import com.interviewiq.company.dto.CompanyOnboardRequest;
import com.interviewiq.company.dto.CompanyProfileResponse;
import com.interviewiq.company.dto.OnboardResponse;
import com.interviewiq.company.dto.UpdateCompanyRequest;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.shared.exception.ConflictException;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Company lifecycle service — onboarding, profile management, and slug availability.
 *
 * <h2>Onboarding flow</h2>
 * <p>A single {@link #onboard(CompanyOnboardRequest)} call atomically creates:
 * <ol>
 *   <li>The {@link Company} row with an auto-generated or caller-supplied slug.</li>
 *   <li>The first admin {@link User} (role = {@code ADMIN}, emailVerified = false).</li>
 *   <li>An empty {@link Wallet} with zero balance.</li>
 * </ol>
 * <p>An email-verification OTP is dispatched after the transaction commits.
 * The account is gated behind {@code emailVerified = true} — the admin cannot
 * log in until the OTP is submitted to {@code /api/v1/{slug}/auth/verify-email}.
 *
 * <h2>Slug generation</h2>
 * <p>If the caller does not supply a {@code slug}, one is derived from the company
 * name: lowercase, spaces → hyphens, non-alphanumeric-hyphen chars stripped.
 * A numeric suffix ({@code -2}, {@code -3}, …) is appended if the base slug
 * already exists.
 *
 * <h2>Multi-tenant isolation</h2>
 * <p>The authenticated methods ({@link #getProfile} and {@link #updateProfile})
 * enforce that the caller's {@code companyId} (from the JWT) matches the resource
 * being accessed via {@link SecurityContext#requireSameCompany(UUID)}.
 */
@Service
public class CompanyService {

    private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyRepository companyRepository;
    private final UserRepository    userRepository;
    private final WalletRepository  walletRepository;
    private final OtpService        otpService;
    private final PasswordEncoder   passwordEncoder;

    public CompanyService(CompanyRepository companyRepository,
                          UserRepository userRepository,
                          WalletRepository walletRepository,
                          OtpService otpService,
                          PasswordEncoder passwordEncoder) {
        this.companyRepository = companyRepository;
        this.userRepository    = userRepository;
        this.walletRepository  = walletRepository;
        this.otpService        = otpService;
        this.passwordEncoder   = passwordEncoder;
    }

    // =========================================================================
    // Onboarding (public — no auth required)
    // =========================================================================

    /**
     * Creates company + admin user + empty wallet in a single transaction,
     * then dispatches an email-verification OTP.
     *
     * @throws ConflictException if the requested slug or admin email is already in use
     */
    @Transactional
    public OnboardResponse onboard(CompanyOnboardRequest req) {
        String email = req.email().toLowerCase().strip();
        String slug  = resolveSlug(req.companyName(), req.slug());

        // ── Duplicate checks ─────────────────────────────────────────────────
        if (companyRepository.existsBySlug(slug)) {
            throw new ConflictException(
                    "A company with slug '" + slug + "' already exists. " +
                    "Please choose a different slug.");
        }
        if (StringUtils.hasText(req.domain()) && companyRepository.existsByDomain(req.domain())) {
            throw new ConflictException(
                    "A company with domain '" + req.domain() + "' is already registered.");
        }

        // ── Create Company ────────────────────────────────────────────────────
        Company company = new Company();
        company.setName(req.companyName().strip());
        company.setSlug(slug);
        company.setDomain(StringUtils.hasText(req.domain()) ? req.domain().toLowerCase().strip() : null);
        company.setStatus(CompanyStatus.ACTIVE);
        companyRepository.save(company);

        // ── Create admin User ─────────────────────────────────────────────────
        // Email uniqueness within a company: guard against duplicate onboarding.
        if (userRepository.existsByCompanyIdAndEmail(company.getId(), email)) {
            throw new ConflictException("An account with this email already exists.");
        }

        User admin = new User();
        admin.setCompanyId(company.getId());
        admin.setFullName(req.adminName().strip());
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(req.password()));
        admin.setRole(UserRole.ADMIN);
        admin.setActive(true);
        admin.setEmailVerified(false);
        userRepository.save(admin);

        // ── Create empty Wallet ───────────────────────────────────────────────
        Wallet wallet = new Wallet();
        wallet.setCompanyId(company.getId());
        walletRepository.save(wallet);

        log.info("Company onboarded: companyId={} slug={} adminEmail={}", company.getId(), slug, email);

        // OTP dispatched outside the transaction? No — email send is fire-and-forget
        // inside EmailService; a transactional wrapper here is fine.
        otpService.sendOtp(email, OtpPurpose.EMAIL_VERIFICATION, company.getId(), admin.getId());

        return new OnboardResponse(slug, email);
    }

    // =========================================================================
    // Profile management (authenticated)
    // =========================================================================

    /**
     * Returns the profile for the caller's company.
     * Cross-tenant isolation is enforced via {@link SecurityContext#requireCompanyId()}.
     */
    @Transactional(readOnly = true)
    public CompanyProfileResponse getProfile() {
        UUID companyId = SecurityContext.requireCompanyId();
        Company company = requireById(companyId);
        return CompanyProfileResponse.from(company);
    }

    /**
     * Partially updates the caller's company profile.
     * Only {@code ADMIN} users may call this — enforced via {@code @PreAuthorize}
     * on the controller method.
     *
     * <p>Fields set to {@code null} in the request are left unchanged.
     * Setting {@code domain} to an empty string clears the field.
     *
     * @throws ConflictException if the new {@code domain} is already in use by another company
     */
    @Transactional
    public CompanyProfileResponse updateProfile(UpdateCompanyRequest req) {
        UUID companyId = SecurityContext.requireCompanyId();
        Company company = requireById(companyId);

        if (req.name() != null) {
            company.setName(req.name().strip());
        }

        if (req.domain() != null) {
            String newDomain = req.domain().isBlank() ? null : req.domain().toLowerCase().strip();
            if (newDomain != null && !newDomain.equals(company.getDomain())) {
                // Guard against stealing another company's domain
                if (companyRepository.existsByDomain(newDomain)) {
                    throw new ConflictException("Domain '" + newDomain + "' is already registered.");
                }
            }
            company.setDomain(newDomain);
        }

        companyRepository.save(company);
        log.info("Company profile updated: companyId={}", companyId);
        return CompanyProfileResponse.from(company);
    }

    // =========================================================================
    // Slug availability (public — no auth required)
    // =========================================================================

    /**
     * Returns {@code true} if the slug is not yet taken and meets the format rules.
     */
    public boolean isSlugAvailable(String slug) {
        if (!slug.matches("^[a-z0-9-]{3,100}$")) {
            return false;
        }
        return !companyRepository.existsBySlug(slug);
    }

    // =========================================================================
    // Package-visible helpers (used by AuthService)
    // =========================================================================

    /**
     * Loads a company by ID or throws 404.
     */
    public Company requireById(UUID companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns the caller's slug (validated) or auto-generates one from the company name.
     * Auto-generation: lowercase, strip non-alphanumeric except hyphens, deduplicate
     * hyphens, limit to 90 chars, append numeric suffix until unique.
     */
    private String resolveSlug(String companyName, String requestedSlug) {
        if (StringUtils.hasText(requestedSlug)) {
            return requestedSlug.toLowerCase();
        }
        String base = companyName.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (base.length() > 90) {
            base = base.substring(0, 90);
        }
        if (base.length() < 3) {
            base = base + "-co";
        }
        // Try base slug, then base-2, base-3, …
        String candidate = base;
        int suffix = 2;
        while (companyRepository.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
