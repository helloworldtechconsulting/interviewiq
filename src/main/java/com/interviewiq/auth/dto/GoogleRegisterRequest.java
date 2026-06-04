package com.interviewiq.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for Google OAuth company registration.
 *
 * <p>The {@code idToken} provides verified name + email from Google.
 * The {@code companyName} is supplied by the user in the registration form
 * (Google does not provide a company name).
 */
public record GoogleRegisterRequest(

        @NotBlank(message = "Google ID token is required")
        String idToken,

        @NotBlank(message = "Company name is required")
        @Size(min = 2, max = 100, message = "Company name must be between 2 and 100 characters")
        String companyName

) {}
