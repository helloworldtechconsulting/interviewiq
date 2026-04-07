package com.interviewiq.team.web;

import com.interviewiq.shared.dto.ApiResponse;
import com.interviewiq.team.dto.InviteTeamMemberRequest;
import com.interviewiq.team.dto.TeamMemberResponse;
import com.interviewiq.team.dto.UpdateMemberRequest;
import com.interviewiq.team.service.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Team management endpoints.
 *
 * <p>All endpoints require an employer JWT (handled by {@code @Order(2)}
 * {@code SecurityFilterChain}). Role-level guards are applied via
 * {@code @PreAuthorize} where required.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/v1/team}            — list all company members (any role)</li>
 *   <li>{@code POST /api/v1/team/invite}      — invite a new member (ADMIN only, 201)</li>
 *   <li>{@code PATCH /api/v1/team/{userId}}   — update role or active status (ADMIN only)</li>
 * </ul>
 *
 * <h2>Invite flow</h2>
 * <p>On invite, the new member receives an email-verification OTP. They must:
 * <ol>
 *   <li>Verify their email at {@code POST /api/v1/{slug}/auth/verify-email}.</li>
 *   <li>Set their own password at {@code POST /api/v1/{slug}/auth/forgot-password}.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/team")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    // =========================================================================
    // List
    // =========================================================================

    /**
     * GET /api/v1/team
     *
     * <p>Returns all members of the caller's company, sorted by name.
     * Accessible by any authenticated employer regardless of role.
     */
    @GetMapping
    public ApiResponse<List<TeamMemberResponse>> listMembers() {
        return ApiResponse.ok(teamService.listMembers());
    }

    // =========================================================================
    // Invite
    // =========================================================================

    /**
     * POST /api/v1/team/invite
     *
     * <p>Creates a new team member in an unverified state and dispatches an
     * email-verification OTP. Only {@code ADMIN} users may invite new members.
     * Returns 201 with the created member's profile.
     */
    @PostMapping("/invite")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TeamMemberResponse> inviteMember(
            @Valid @RequestBody InviteTeamMemberRequest request) {
        return ApiResponse.created(teamService.inviteMember(request));
    }

    // =========================================================================
    // Update
    // =========================================================================

    /**
     * PATCH /api/v1/team/{userId}
     *
     * <p>Partially updates a team member's role and/or active status.
     * Only {@code ADMIN} users may make this call. Fields omitted from the
     * request body (i.e. sent as {@code null}) are left unchanged.
     *
     * <p>Admins cannot update their own record — this prevents accidental
     * self-demotion or self-deactivation that would lock out the last admin.
     */
    @PatchMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TeamMemberResponse> updateMember(
            @PathVariable UUID userId,
            @RequestBody UpdateMemberRequest request) {
        return ApiResponse.ok(teamService.updateMember(userId, request));
    }
}
