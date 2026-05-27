package com.interviewiq.auth.dto;

import com.interviewiq.auth.domain.User;
import com.interviewiq.auth.domain.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
        UUID           id,
        UUID           companyId,
        String         fullName,
        String         email,
        UserRole       role,
        boolean        active,
        boolean        emailVerified,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getCompanyId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.isActive(),
                user.isEmailVerified(),
                user.getLastLoginAt(),
                user.getCreatedAt()
        );
    }
}
