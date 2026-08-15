package com.interviewiq.company.infrastructure;

import com.interviewiq.company.domain.Company;
import com.interviewiq.company.domain.CompanyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findBySlug(String slug);

    Optional<Company> findByDomain(String domain);

    boolean existsBySlug(String slug);

    boolean existsByDomain(String domain);

    /**
     * Any company on this domain that already received a signup grant.
     *
     * <p>The domain-level abuse guard from §7.8.3: one grant per verified
     * corporate domain, so a company cannot re-register under a second name on
     * the same domain to mint another set of free interviews.
     */
    Optional<Company> findFirstByDomainIgnoreCaseAndPromoGrantAppliedAtIsNotNull(String domain);
}
