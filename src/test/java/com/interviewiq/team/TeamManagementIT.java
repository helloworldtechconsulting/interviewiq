package com.interviewiq.team;

import com.interviewiq.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("TODO: pending TeamController contract review")
class TeamManagementIT extends AbstractIntegrationTest {

    @Test
    void inviteListPatchTeamMember_adminOnly() {
        // Admin invites recruiter → 201
        // Recruiter attempts invite → 403
        // GET /team → admin sees both
        // PATCH /team/{id} role → 200
    }
}
