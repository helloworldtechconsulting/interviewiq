package com.interviewiq.team.service;

import com.interviewiq.auth.domain.OtpPurpose;
import com.interviewiq.auth.domain.User;
import com.interviewiq.auth.infrastructure.UserRepository;
import com.interviewiq.auth.service.OtpService;
import com.interviewiq.shared.exception.ConflictException;
import com.interviewiq.shared.exception.AuthorizationException;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.security.SecurityContext;
import com.interviewiq.team.dto.InviteTeamMemberRequest;
import com.interviewiq.team.dto.TeamMemberResponse;
import com.interviewiq.team.dto.UpdateMemberRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Team management service — listing, inviting, and updating company members.
 *
 * <h2>Invite flow</h2>
 * <ol>
 *   <li>Admin calls {@link #inviteMember} with name, email, and role.</li>
 *   <li>A {@link User} row is created with {@code emailVerified = false} and a
 *       randomly generated, unknown temporary password (satisfies the DB
 *       {@code ck_users_auth_method} constraint).</li>
 *   <li>An {@code EMAIL_VERIFICATION} OTP is dispatched to the invitee's address.</li>
 *   <li>Invitee calls {@code POST /api/v1/{slug}/auth/verify-email} → account activated.</li>
 *   <li>Invitee calls {@code POST /api/v1/{slug}/auth/forgot-password} → sets own password.</li>
 * </ol>
 *
 * <h2>Self-update guard</h2>
 * <p>An admin cannot change their own role or deactivate themselves — doing so
 * could lock out the last admin, creating an unrecoverable tenant state.
 *
 * <h2>Multi-tenant isolation</h2>
 * <p>All queries are scoped to {@code companyId} extracted from the caller's JWT
 * via {@link SecurityContext#requireCompanyId()}. Cross-tenant updates are
 * structurally impossible because {@link #requireMemberInCompany} enforces
 * the company scope on every lookup.
 */
@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private final UserRepository  userRepository;
    private final OtpService      otpService;
    private final PasswordEncoder passwordEncoder;

    public TeamService(UserRepository userRepository,
                       OtpService otpService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository  = userRepository;
        this.otpService      = otpService;
        this.passwordEncoder = passwordEncoder;
    }

    // =========================================================================
    // List
    // =========================================================================

    /**
     * Returns all users belonging to the caller's company, ordered by name.
     * Any authenticated employer (ADMIN / RECRUITER / VIEWER) may call this.
     */
    @Transactional(readOnly = true)
    public List<TeamMemberResponse> listMembers() {
        UUID companyId = SecurityContext.requireCompanyId();
        return userRepository.findAllByCompanyIdOrderByFullNameAsc(companyId)
                .stream()
                .map(TeamMemberResponse::from)
                .toList();
    }

    // =========================================================================
    // Invite
    // =========================================================================

    /**
     * Creates a new team member account in an unverified state and dispatches
     * an email-verification OTP.
     *
     * <p>The invitee must verify their email (via
     * {@code POST /api/v1/{slug}/auth/verify-email}) and then set their own
     * password (via {@code POST /api/v1/{slug}/auth/forgot-password}) before
     * they can log in.
     *
     * @throws ConflictException if the email is already registered in this company
     */
    @Transactional
    public TeamMemberResponse inviteMember(InviteTeamMemberRequest req) {
        UUID companyId = SecurityContext.requireCompanyId();
        String email = req.email().toLowerCase().strip();

        if (userRepository.existsByCompanyIdAndEmail(companyId, email)) {
            throw new ConflictException(
                    "A team member with email '" + email + "' already exists in this company.");
        }

        // Generate a random temporary password — not communicated to anyone.
        // The invitee will reset their password after email verification.
        // Using UUID.randomUUID() gives 122 bits of entropy; BCrypt adds its own salt.
        String tempPassword = UUID.randomUUID().toString() + UUID.randomUUID();

        User user = new User();
        user.setCompanyId(companyId);
        user.setFullName(req.fullName().strip());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        user.setRole(req.role());
        user.setActive(true);
        user.setEmailVerified(false);
        userRepository.save(user);

        log.info("Team member invited: companyId={} userId={} email={} role={}",
                companyId, user.getId(), email, req.role());

        // Dispatch email-verification OTP so the invitee can activate their account.
        otpService.sendOtp(email, OtpPurpose.EMAIL_VERIFICATION, companyId, user.getId());

        return TeamMemberResponse.from(user);
    }

    // =========================================================================
    // Update
    // =========================================================================

    /**
     * Partially updates a team member's role or active status.
     *
     * <p>Only {@code ADMIN} users may call this — enforced via {@code @PreAuthorize}
     * on the controller. Fields set to {@code null} are left unchanged.
     *
     * @param userId  UUID of the member to update
     * @param request fields to update (null fields are ignored)
     * @throws ResourceNotFoundException if the user does not belong to the caller's company
     * @throws AuthorizationException    if the caller attempts to update their own account
     */
    @Transactional
    public TeamMemberResponse updateMember(UUID userId, UpdateMemberRequest request) {
        UUID companyId  = SecurityContext.requireCompanyId();
        UUID callerId   = SecurityContext.requireUserId();

        // Self-update guard: prevents an admin from accidentally locking themselves out.
        if (userId.equals(callerId)) {
            throw new AuthorizationException(
                    "You cannot change your own role or active status. " +
                    "Ask another admin to make this change.");
        }

        User member = requireMemberInCompany(companyId, userId);

        if (request.role() != null) {
            member.setRole(request.role());
        }
        if (request.active() != null) {
            member.setActive(request.active());
        }

        userRepository.save(member);
        log.info("Team member updated: companyId={} userId={} role={} active={}",
                companyId, userId, member.getRole(), member.isActive());

        return TeamMemberResponse.from(member);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Loads a {@link User} by company + user UUID, or throws 404.
     * Enforces tenant isolation — cross-company lookups are structurally prevented.
     */
    private User requireMemberInCompany(UUID companyId, UUID userId) {
        return userRepository.findByCompanyIdAndId(companyId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Team member", userId));
    }
}
