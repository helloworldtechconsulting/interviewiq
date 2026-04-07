package com.interviewiq.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for the company onboarding endpoint.
 *
 * <p>Creates a company, its first admin user, and an empty wallet atomically.
 * If {@code slug} is omitted the service generates one from {@code companyName}.
 *
 * @param companyName the display name of the company (e.g. "Acme Corp")
 * @param slug        optional URL-safe identifier; auto-generated when blank.
 *                    Must be lowercase alphanumeric + hyphens, 3–100 chars.
 * @param domain      optional corporate email domain (e.g. "acme.com");
 *                    used for Google OAuth domain-based resolution
 * @param adminName   full name of the first admin user
 * @param email       admin user's email address
 * @param password    admin user's initial password (min 8 chars)
 */
public record CompanyOnboardRequest(

        @NotBlank(message = "Company name is required.")
        @Size(max = 255, message = "Company name must be at most 255 characters.")
        String companyName,

        @Size(min = 3, max = 100, message = "Slug must be between 3 and 100 characters.")
        @Pattern(regexp = "^[a-z0-9-]*$",
                message = "Slug may only contain lowercase letters, digits, and hyphens.")
        String slug,   // nullable — auto-generated when blank

        @Size(max = 255, message = "Domain must be at most 255 characters.")
        String domain, // nullable

        @NotBlank(message = "Admin name is required.")
        @Size(max = 255, message = "Admin name must be at most 255 characters.")
        String adminName,

        @NotBlank(message = "Email is required.")
        @Email(message = "Must be a valid email address.")
        @Size(max = 255, message = "Email must be at most 255 characters.")
        String email,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters.")
        String password
) {}
