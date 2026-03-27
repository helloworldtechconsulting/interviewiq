package com.interviewiq.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleOAuthRequest(
        @NotBlank(message = "Google ID token is required")
        String idToken,

        String companyName,

        String companyDomain
) {
}
