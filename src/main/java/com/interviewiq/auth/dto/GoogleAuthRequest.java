package com.interviewiq.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for Google OAuth login/link endpoints.
 *
 * <p>The {@code idToken} is the raw Google ID token JWT string returned by
 * the {@code @react-oauth/google} frontend library's {@code GoogleLogin}
 * component ({@code credential} field in the callback response).
 */
public record GoogleAuthRequest(

        @NotBlank(message = "Google ID token is required")
        String idToken

) {}
