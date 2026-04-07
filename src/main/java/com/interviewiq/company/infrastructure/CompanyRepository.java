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
}
