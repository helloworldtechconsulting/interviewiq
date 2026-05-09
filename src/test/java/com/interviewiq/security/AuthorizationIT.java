package com.interviewiq.security;

import com.interviewiq.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-cutting authorization tests. Only the no-token / bad-token assertions
 * are exercised here — cross-tenant isolation requires authenticated helpers
 * and is left as a TODO (covered by repository-level scoping tests in unit
 * tests). The /api/v1/companies/me path is confirmed authenticated in
 * {@code SecurityConfig} (anyRequest().authenticated()).
 */
class AuthorizationIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("Authenticated endpoint returns 401 when no JWT is supplied")
    void missingJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/companies/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated endpoint returns 401 for malformed token")
    void malformedJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/companies/me")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }
}
