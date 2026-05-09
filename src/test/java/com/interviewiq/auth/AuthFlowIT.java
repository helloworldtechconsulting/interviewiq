package com.interviewiq.auth;

import com.interviewiq.support.AbstractIntegrationTest;
import com.interviewiq.support.TestDataFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth funnel.
 *
 * <p>Full flow:
 * <ol>
 *   <li>POST {@code /api/v1/companies/register} → Company + admin user created.</li>
 *   <li>Read the OTP from {@code OtpRecord} repository (BCrypt-hashed — see TODO).</li>
 *   <li>POST {@code /api/v1/{slug}/auth/verify-email} with the OTP → user verified, tokens issued.</li>
 *   <li>POST {@code /api/v1/{slug}/auth/login} → fresh token pair.</li>
 *   <li>POST {@code /api/v1/{slug}/auth/refresh} → token rotation.</li>
 *   <li>POST {@code /api/v1/{slug}/auth/logout} → refresh tokens revoked.</li>
 * </ol>
 *
 * <p>The registration leg runs as a smoke test. The OTP-dependent legs are
 * {@code @Disabled} pending a test seam: {@code OtpRecord.otpHash} is a BCrypt
 * hash, so tests cannot recover the raw 6-digit code from the repository.
 * Options to unblock:
 * <ul>
 *   <li>Inject a Mockito spy on {@code OtpService.sendOtp} that captures the
 *       generated raw OTP into a thread-local before delegating.</li>
 *   <li>Add a profile-gated {@code OtpService#peekRawForTest(email)} method
 *       (test profile only) that stashes raw codes in an in-memory map.</li>
 *   <li>Stub {@code EmailService} and capture the OTP from the rendered
 *       email body.</li>
 * </ul>
 */
class AuthFlowIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("POST /companies/register persists company + admin (smoke)")
    void registerLeg_returns201() throws Exception {
        Map<String, Object> body = TestDataFactory.companyOnboardRequest(
                "Acme Auth Test", TestDataFactory.uniqueEmail());

        mockMvc.perform(post("/api/v1/companies/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").exists())
                .andExpect(jsonPath("$.data.email").exists());
    }

    @Test
    @Disabled("TODO: needs a test seam to recover raw OTP — see class Javadoc")
    void verifyEmailThenLoginRefreshLogout() {
        // Once the OTP capture seam is in place:
        //   1. POST /api/v1/{slug}/auth/verify-email { email, otp } → 200 + AuthResponse
        //   2. POST /api/v1/{slug}/auth/login         → 200 + AuthResponse
        //   3. POST /api/v1/{slug}/auth/refresh       → 200 + new tokens, old refresh revoked
        //   4. POST /api/v1/{slug}/auth/logout        → 204, no refresh token rows remain
    }
}
