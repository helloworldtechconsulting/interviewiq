package com.interviewiq.company.dto;

import com.interviewiq.company.domain.Company;
import com.interviewiq.company.domain.CompanyStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only projection of a {@link Company} for API responses.
 *
 * @param id        company UUID
 * @param name      display name
 * @param slug      URL-safe identifier (immutable)
 * @param domain    optional corporate email domain
 * @param status    account status (ACTIVE / INACTIVE / SUSPENDED)
 * @param createdAt UTC timestamp when the company was created
 */
public record CompanyProfileResponse(
        UUID id,
        String name,
        String slug,
        String domain,
        CompanyStatus status,
        OffsetDateTime createdAt
) {
    /** Factory method — converts a {@link Company} entity to this DTO. */
    public static CompanyProfileResponse from(Company c) {
        return new CompanyProfileResponse(
                c.getId(),
                c.getName(),
                c.getSlug(),
                c.getDomain(),
                c.getStatus(),
                c.getCreatedAt()
        );
    }
}
