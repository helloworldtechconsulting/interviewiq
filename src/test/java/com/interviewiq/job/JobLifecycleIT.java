package com.interviewiq.job;

import com.interviewiq.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Job lifecycle integration test.
 *
 * <p>The full create→list→get→patch→delete happy path requires a registered
 * company + verified admin + bearer token, which depends on the
 * {@code OtpService}-issued raw OTP being captured by the test (see
 * {@link com.interviewiq.auth.AuthFlowIT}). Until that helper is in place the
 * authenticated leg of this test is guarded by {@code @Disabled} below.
 *
 * <p>This file does exercise the unauthenticated leg — every {@code /api/v1/jobs}
 * route returns 401 without a JWT — to ring-fence the security configuration.
 */
class JobLifecycleIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("GET /api/v1/jobs without JWT returns 401")
    void listJobs_returns401_withoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/jobs without JWT returns 401")
    void createJob_returns401_withoutJwt() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Disabled("TODO: enable once registerCompanyWithAdmin + loginAndGetToken helpers exist " +
              "(blocked on capturing raw OTP — see AuthFlowIT TODO)")
    void createListGetPatchDeleteJob() {
        // 1. registerCompanyWithAdmin() → bearer token
        // 2. POST /api/v1/jobs (CreateJobRequest) → 201 + JobResponse
        // 3. GET  /api/v1/jobs → page contains the new job
        // 4. GET  /api/v1/jobs/{id} → 200, body matches creation
        // 5. PATCH /api/v1/jobs/{id} → 200, fields updated
        // 6. DELETE /api/v1/jobs/{id} → 204
        // 7. GET /api/v1/jobs/{id} → 200, status = CLOSED (soft-deleted)
    }
}
