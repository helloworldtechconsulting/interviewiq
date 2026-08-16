package com.interviewengine.team.dto;

import com.interviewengine.auth.domain.User;
import com.interviewengine.auth.domain.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only projection of a {@link User} for team management API responses.
 *
 * <p>Password hashes, Google subjects, and OTP state are intentionally excluded.
 *
 * @param id            user UUID
 * @param fullName      display name
 * @param email         lowercase email address
 * @param role          current role (ADMIN / RECRUITER / VIEWER)
 * @param active        whether the account is active (false = soft-deleted)
 * @param emailVerified whether the user has completed email verification
 * @param createdAt     UTC timestamp when the account was created
 */
public record TeamMemberResponse(
        UUID id,
        String fullName,
        String email,
        UserRole role,
        boolean active,
        boolean emailVerified,
        OffsetDateTime createdAt
) {
    /** Factory method — converts a {@link User} entity to this DTO. */
    public static TeamMemberResponse from(User u) {
        return new TeamMemberResponse(
                u.getId(),
                u.getFullName(),
                u.getEmail(),
                u.getRole(),
                u.isActive(),
                u.isEmailVerified(),
                u.getCreatedAt()
        );
    }
}
