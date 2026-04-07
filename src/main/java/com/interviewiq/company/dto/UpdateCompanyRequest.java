package com.interviewiq.company.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial-update request for company profile settings.
 *
 * <p>Only the fields that are explicitly provided (non-null) are updated.
 * {@code slug} is intentionally excluded — slugs are immutable after creation
 * to preserve existing bookmarks and integrations.
 *
 * @param name   updated display name; null means "do not change"
 * @param domain updated corporate email domain; null means "do not change";
 *               empty string means "clear the domain"
 */
public record UpdateCompanyRequest(

        @Size(max = 255, message = "Company name must be at most 255 characters.")
        String name,   // nullable

        @Size(max = 255, message = "Domain must be at most 255 characters.")
        String domain  // nullable
) {}
