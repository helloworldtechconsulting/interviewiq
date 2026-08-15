package com.interviewiq.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.auth.dto.AuthResponse;
import com.interviewiq.auth.domain.UserRole;
import com.interviewiq.auth.dto.UserResponse;
import com.interviewiq.auth.service.AuthService;
import com.interviewiq.auth.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice test for {@link AuthController}.
 *
 * <p>Focuses on request mapping, bean validation, and HTTP response contracts.
 * Security filters are excluded so tests exercise controller logic in isolation —
 * a dedicated security integration test is the right place for filter-chain behaviour.
 *
 * <p>The {@link com.interviewiq.shared.web.GlobalExceptionHandler} is imported
 * explicitly so that validation errors translate to structured 400 responses.
 */
@WebMvcTest(
        controllers = AuthController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class
        }
)
// RefreshTokenCookie is imported as a REAL bean rather than mocked: the cookie
// attributes it sets are precisely what the login test asserts, and a mock would
// let the test pass while shipping a cookie with no HttpOnly flag.
@Import({com.interviewiq.shared.web.GlobalExceptionHandler.class,
         RefreshTokenCookie.class,
         AuthControllerTest.TestConfig.class})
class AuthControllerTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        com.interviewiq.auth.config.SecurityProperties securityProperties() {
            return new com.interviewiq.auth.config.SecurityProperties();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AuthService authService;

    private static final String SLUG = "acme";

    // =========================================================================
    // POST /register — 201 on success, 400 on validation failure
    // =========================================================================

    @Test
    void register_returns201_whenRequestIsValid() throws Exception {
        doNothing().when(authService).register(eq(SLUG), any());

        mockMvc.perform(post("/api/v1/{slug}/auth/register", SLUG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "full_name": "Alice Smith",
                                  "email": "alice@example.com",
                                  "password": "Secur3Pass!"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void register_returns400_whenEmailIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/{slug}/auth/register", SLUG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "full_name": "Alice Smith",
                                  "email": "",
                                  "password": "Secur3Pass!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").exists());

        verifyNoInteractions(authService);
    }

    @Test
    void register_returns400_whenPasswordTooShort() throws Exception {
        mockMvc.perform(post("/api/v1/{slug}/auth/register", SLUG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "full_name": "Alice Smith",
                                  "email": "alice@example.com",
                                  "password": "short"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    // =========================================================================
    // POST /login — 200 on success
    // =========================================================================

    @Test
    void login_returnsAccessTokenInBodyAndRefreshTokenInAnHttpOnlyCookie() throws Exception {
        AuthResponse.WithRefreshToken issued = new AuthResponse.WithRefreshToken(
                new AuthResponse(
                        "access.token.jwt",
                        new UserResponse(UUID.randomUUID(), UUID.randomUUID(),
                                "Alice Smith", "alice@example.com", UserRole.ADMIN,
                                true, true, java.time.OffsetDateTime.now(),
                                java.time.OffsetDateTime.now())),
                "refresh-uuid");
        when(authService.login(eq(SLUG), any(), any())).thenReturn(issued);

        var result = mockMvc.perform(post("/api/v1/{slug}/auth/login", SLUG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "alice@example.com",
                                  "password": "Secur3Pass!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").value("access.token.jwt"))
                // PRD v2.1 §7.1.1: the refresh token must never reach JavaScript,
                // so it must NOT appear in the response body at all.
                .andExpect(jsonPath("$.data.refresh_token").doesNotExist())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie)
                .as("refresh token must travel in an HTTP-only cookie")
                .isNotNull()
                .contains("iiq_refresh=refresh-uuid")
                .contains("HttpOnly")     // the actual XSS mitigation
                .contains("Secure")       // never over plaintext
                .contains("SameSite=None"); // app.* calling api.* is cross-site
    }

    @Test
    void login_returns400_whenEmailIsInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/{slug}/auth/login", SLUG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "password": "Secur3Pass!"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    // =========================================================================
    // POST /resend-verification — always 200 (no user enumeration)
    // =========================================================================

    @Test
    void resendVerification_returns200_regardless() throws Exception {
        doNothing().when(authService).resendVerification(any(), any());

        mockMvc.perform(post("/api/v1/{slug}/auth/resend-verification", SLUG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "unknown@example.com" }
                                """))
                .andExpect(status().isOk());
    }
}
