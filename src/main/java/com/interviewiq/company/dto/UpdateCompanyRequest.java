package com.interviewiq.company.dto;

import com.interviewiq.company.domain.CompanySize;
import jakarta.validation.constraints.Size;

/**
 * Partial-update request for company profile settings.
 *
 * <p>Only the fields that are explicitly provided (non-null) are updated.
 * {@code slug} is intentionally excluded — slugs are immutable after creation
 * to preserve existing bookmarks and integrations.
 *
 * @param name      updated display name; null means "do not change"
 * @param domain    updated corporate email domain; null = no change; empty = clear
 * @param website   company website URL; null = no change; empty = clear
 * @param industry  industry classification; null = no change
 * @param size      headcount band; null = no change
 * @param gstNumber GST number for invoicing; null = no change; empty = clear
 */
public record UpdateCompanyRequest(

        @Size(max = 255, message = "Company name must be at most 255 characters.")
        String name,

        @Size(max = 255, message = "Domain must be at most 255 characters.")
        String domain,

        String website,

        @Size(max = 100, message = "Industry must be at most 100 characters.")
        String industry,

        CompanySize size,

        @Size(max = 20, message = "GST number must be at most 20 characters.")
        String gstNumber
) {}
