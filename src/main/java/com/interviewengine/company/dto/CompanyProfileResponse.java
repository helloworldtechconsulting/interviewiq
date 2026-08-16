package com.interviewengine.company.dto;

import com.interviewengine.company.domain.Company;
import com.interviewengine.company.domain.CompanySize;
import com.interviewengine.company.domain.CompanyStatus;

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
 * @param website   company website URL
 * @param industry  industry classification
 * @param logoS3Key S3 object key for the company logo (null if not set)
 * @param size      headcount band
 * @param gstNumber GST registration number for invoicing
 * @param createdAt UTC timestamp when the company was created
 */
public record CompanyProfileResponse(
        UUID          id,
        String        name,
        String        slug,
        String        domain,
        CompanyStatus status,
        String        website,
        String        industry,
        String        logoS3Key,
        CompanySize   size,
        String        gstNumber,
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
                c.getWebsite(),
                c.getIndustry(),
                c.getLogoS3Key(),
                c.getSize(),
                c.getGstNumber(),
                c.getCreatedAt()
        );
    }
}
