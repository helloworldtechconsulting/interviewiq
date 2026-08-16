package com.interviewengine.team.dto;

import com.interviewengine.auth.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/team/invite}.
 *
 * <p>The admin supplies the invitee's name, email, and the role they should
 * be granted. The system creates the account in an unverified state and
 * dispatches an email-verification OTP so the invitee can activate their
 * account and set their own password via the forgot-password flow.
 *
 * @param fullName display name for the new team member
 * @param email    invitee's work email address (must be unique within the company)
 * @param role     initial role: {@code ADMIN}, {@code RECRUITER}, or {@code VIEWER}
 */
public record InviteTeamMemberRequest(

        @NotBlank(message = "Full name is required.")
        @Size(max = 255, message = "Full name must not exceed 255 characters.")
        String fullName,

        @NotBlank(message = "Email is required.")
        @Email(message = "Must be a valid email address.")
        @Size(max = 255, message = "Email must not exceed 255 characters.")
        String email,

        @NotNull(message = "Role is required.")
        UserRole role

) {}
