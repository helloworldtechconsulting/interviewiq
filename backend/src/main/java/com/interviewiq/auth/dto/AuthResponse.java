package com.interviewiq.auth.dto;

import java.util.UUID;

public record AuthResponse(
        UUID userId,
        UUID companyId,
        String email,
        String name,
        String role,
        String accessToken,
        String refreshToken,
        Long expiresIn
) {
}
