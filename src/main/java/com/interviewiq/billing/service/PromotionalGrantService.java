package com.interviewiq.billing.service;

import com.interviewiq.company.domain.Company;
import com.interviewiq.company.infrastructure.CompanyRepository;
import com.interviewiq.shared.config.BillingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a company may receive the self-serve signup grant, and applies
 * it (PRD v2.1 §7.8.3).
 *
 * <p>The grant itself is worth about three free interviews and exists because
 * "nobody buys an AI interviewer they have not watched run" — the PRD calls it
 * the single highest-leverage item for reaching 20 paying clients in 60 days.
 * Which is precisely why it needs guarding: a free-interview faucet with no
 * controls is a free-interview faucet.
 *
 * <h2>The abuse guards</h2>
 *
 * <p>§7.8.3 specifies three, and they are layered rather than alternatives:
 *
 * <ol>
 *   <li><strong>One grant per company.</strong> Recorded as
 *       {@code promoGrantAppliedAt} on the company itself, so a repeated
 *       verification cannot re-trigger it.</li>
 *   <li><strong>Email-domain deduplication,</strong> with public free-mail
 *       domains treated more strictly. A corporate domain identifies an
 *       organisation, so one grant per verified domain is meaningful; gmail.com
 *       identifies nothing, so granting per verified gmail address would be
 *       granting per email address, and anyone can mint those.</li>
 *   <li><strong>Total exposure cap,</strong> enforced in
 *       {@link WalletService#grantPromotionalCredit}.</li>
 * </ol>
 *
 * <p>Payment-instrument deduplication is the fourth guard named in §7.8.3 — a
 * card or VPA already associated with a granted company cannot unlock a second
 * grant. It is enforced at top-up time rather than here, because the instrument
 * is not known until the company first pays.
 */
@Service
public class PromotionalGrantService {

    private static final Logger log = LoggerFactory.getLogger(PromotionalGrantService.class);

    private final CompanyRepository companyRepository;
    private final WalletService walletService;
    private final BillingProperties billingProperties;

    public PromotionalGrantService(CompanyRepository companyRepository,
                                   WalletService walletService,
                                   BillingProperties billingProperties) {
        this.companyRepository = companyRepository;
        this.walletService     = walletService;
        this.billingProperties = billingProperties;
    }

    /**
     * Applies the signup grant if this company qualifies.
     *
     * <p>Called on successful email verification (§7.1.1). Deliberately returns a
     * boolean rather than throwing when the grant does not apply: a company that
     * already has one, or a domain that has already been granted, is a normal
     * outcome of verification and must not fail the user's signup.
     *
     * @param companyId    the newly verified company
     * @param verifiedEmail the address that was verified — its domain is the guard
     * @return true if a grant was applied
     */
    @Transactional
    public boolean applySignupGrantIfEligible(UUID companyId, String verifiedEmail) {
        BillingProperties.SignupGrant config = billingProperties.getSignupGrant();
        if (!config.isEnabled() || config.getAmountPaise() <= 0) {
            return false;
        }

        Company company = companyRepository.findById(companyId).orElse(null);
        if (company == null) {
            log.warn("Signup grant skipped — company not found: companyId={}", companyId);
            return false;
        }

        // Guard 1 — one per company.
        if (company.hasReceivedSignupGrant()) {
            log.debug("Signup grant skipped — already granted: companyId={}", companyId);
            return false;
        }

        // Guard 2 — one per corporate domain. Public free-mail domains are not
        // deduplicated by domain (every user shares gmail.com), so for those the
        // per-company guard is the only domain-level control.
        String domain = domainOf(verifiedEmail);
        if (domain != null && !isPublicEmailDomain(domain)) {
            Optional<Company> alreadyGranted = companyRepository
                    .findFirstByDomainIgnoreCaseAndPromoGrantAppliedAtIsNotNull(domain);
            if (alreadyGranted.isPresent() && !alreadyGranted.get().getId().equals(companyId)) {
                log.info("Signup grant refused — domain already granted: companyId={} domain={}",
                        companyId, domain);
                return false;
            }
        }

        boolean granted = walletService.applySignupGrant(companyId, null);
        if (granted) {
            company.setPromoGrantAppliedAt(OffsetDateTime.now(ZoneOffset.UTC));
            companyRepository.save(company);
            log.info("Signup grant applied: companyId={} amountPaise={} domain={}",
                    companyId, config.getAmountPaise(), domain);
        }
        return granted;
    }

    /**
     * Whether a domain is a public free-mail provider, which §7.8.3 requires be
     * "treated more strictly" than a corporate domain.
     */
    public boolean isPublicEmailDomain(String domain) {
        return billingProperties.getSignupGrant().getPublicEmailDomains()
                .contains(domain.toLowerCase(Locale.ROOT));
    }

    private String domainOf(String email) {
        if (email == null) return null;
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) return null;
        return email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }
}
