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
@Import(com.interviewiq.shared.web.GlobalExceptionHandler.class)
class AuthControllerTest {

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
    void login_returns200_withTokenPairOnSuccess() throws Exception {
        AuthResponse response = new AuthResponse(
                "access.token.jwt",
                "refresh-uuid",
                new UserResponse(UUID.randomUUID(), UUID.randomUUID(),
                        "Alice Smith", "alice@example.com", UserRole.ADMIN,
                        true, true, java.time.OffsetDateTime.now(),
                        java.time.OffsetDateTime.now())
        );
        when(authService.login(eq(SLUG), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/{slug}/auth/login", SLUG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "alice@example.com",
                                  "password": "Secur3Pass!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").value("access.token.jwt"))
                .andExpect(jsonPath("$.data.refresh_token").value("refresh-uuid"));
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
