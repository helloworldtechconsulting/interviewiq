package com.interviewengine.team.dto;

import com.interviewengine.auth.domain.UserRole;

/**
 * Request body for {@code PATCH /api/v1/team/{userId}}.
 *
 * <p>Partial update — any field left {@code null} is not modified.
 *
 * <ul>
 *   <li>{@code role} — reassign the member's role ({@code ADMIN}, {@code RECRUITER},
 *       {@code VIEWER}). Admins cannot change their own role to prevent
 *       accidental self-demotion.</li>
 *   <li>{@code active} — set to {@code false} to deactivate (soft-delete), or
 *       {@code true} to re-activate a previously deactivated account. Admins
 *       cannot deactivate themselves.</li>
 * </ul>
 *
 * @param role   new role, or {@code null} to leave unchanged
 * @param active new active flag, or {@code null} to leave unchanged
 */
public record UpdateMemberRequest(
        UserRole role,
        Boolean active
) {}
