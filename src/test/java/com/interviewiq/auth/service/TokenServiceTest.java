package com.interviewiq.auth.service;

import com.interviewiq.auth.config.SecurityProperties;
import com.interviewiq.auth.domain.User;
import com.interviewiq.auth.domain.UserRole;
import com.interviewiq.auth.exception.InvalidTokenException;
import com.interviewiq.auth.exception.TokenExpiredException;
import com.interviewiq.auth.service.dto.AccessTokenClaims;
import com.interviewiq.auth.service.dto.InviteTokenClaims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TokenService}.
 *
 * <p>No Spring context — the service is wired directly with ephemeral test keys.
 * Tests cover three token types: RS256 access tokens, rotating refresh tokens,
 * and HS256 invite tokens.
 */
class TokenServiceTest {

    private TokenService tokenService;

    private UUID userId;
    private UUID companyId;
    private UUID sessionId;
    private UUID candidateId;

    @BeforeEach
    void setUp() {
        // Ephemeral keys — identical pattern to production local mode
        KeyPair keyPair   = Jwts.SIG.RS256.keyPair().build();
        SecretKey hmacKey = Jwts.SIG.HS256.key().build();

        SecurityProperties props = new SecurityProperties();
        props.getJwt().setAccessTokenExpiration(Duration.ofMinutes(15));
        props.getInvite().setExpiration(Duration.ofDays(7));

        tokenService = new TokenService(keyPair, hmacKey, props);

        userId      = UUID.randomUUID();
        companyId   = UUID.randomUUID();
        sessionId   = UUID.randomUUID();
        candidateId = UUID.randomUUID();
    }

    // =========================================================================
    // Access token
    // =========================================================================

    @Test
    void generateAccessToken_roundTrip_allClaimsPreserved() {
        User user = buildUser(userId, companyId, "alice@example.com", UserRole.ADMIN);

        String token = tokenService.generateAccessToken(user);

        AccessTokenClaims claims = tokenService.validateAccessToken(token);
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.companyId()).isEqualTo(companyId);
        assertThat(claims.email()).isEqualTo("alice@example.com");
        assertThat(claims.role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void validateAccessToken_throwsInvalidToken_whenSignatureTampered() {
        User user  = buildUser(userId, companyId, "bob@example.com", UserRole.RECRUITER);
        String jwt = tokenService.generateAccessToken(user);

        // Flip several signature bytes to guarantee a meaningful corruption
        // (single-char base64 flips can be no-ops if the char is padding).
        int dot = jwt.lastIndexOf('.');
        String header     = jwt.substring(0, dot + 1);
        String signature  = jwt.substring(dot + 1);
        // Reverse the signature — virtually certain to be invalid
        String tampered = header + new StringBuilder(signature).reverse();

        assertThatThrownBy(() -> tokenService.validateAccessToken(tampered))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void validateAccessToken_throwsInvalidToken_whenInviteTokenPassedInstead() {
        // Invite token signed with HMAC key, not RSA — type guard must also catch this
        String inviteToken = tokenService.generateInviteToken(sessionId, candidateId, companyId);

        assertThatThrownBy(() -> tokenService.validateAccessToken(inviteToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void validateAccessToken_throwsTokenExpired_whenExpired() throws InterruptedException {
        SecurityProperties shortLived = new SecurityProperties();
        shortLived.getJwt().setAccessTokenExpiration(Duration.ofMillis(1));
        shortLived.getInvite().setExpiration(Duration.ofDays(7));

        KeyPair keyPair   = Jwts.SIG.RS256.keyPair().build();
        SecretKey hmacKey = Jwts.SIG.HS256.key().build();
        TokenService shortService = new TokenService(keyPair, hmacKey, shortLived);

        User user  = buildUser(userId, companyId, "carol@example.com", UserRole.ADMIN);
        String jwt = shortService.generateAccessToken(user);

        Thread.sleep(10); // ensure expiry has passed

        assertThatThrownBy(() -> shortService.validateAccessToken(jwt))
                .isInstanceOf(TokenExpiredException.class);
    }

    // =========================================================================
    // Invite token
    // =========================================================================

    @Test
    void generateInviteToken_roundTrip_allClaimsPreserved() {
        String token = tokenService.generateInviteToken(sessionId, candidateId, companyId);

        InviteTokenClaims claims = tokenService.validateInviteToken(token);
        assertThat(claims.sessionId()).isEqualTo(sessionId);
        assertThat(claims.candidateId()).isEqualTo(candidateId);
        assertThat(claims.companyId()).isEqualTo(companyId);
    }

    @Test
    void validateInviteToken_throwsInvalidToken_whenAccessTokenPassedInstead() {
        User user        = buildUser(userId, companyId, "dave@example.com", UserRole.RECRUITER);
        String accessJwt = tokenService.generateAccessToken(user);

        assertThatThrownBy(() -> tokenService.validateInviteToken(accessJwt))
                .isInstanceOf(InvalidTokenException.class);
    }

    // =========================================================================
    // Refresh token hashing
    // =========================================================================

    @Test
    void hashToken_isDeterministic() {
        String raw  = UUID.randomUUID().toString();
        String hash1 = tokenService.hashToken(raw);
        String hash2 = tokenService.hashToken(raw);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 → 32 bytes → 64 hex chars
    }

    @Test
    void hashToken_differentInputsProduceDifferentHashes() {
        String hash1 = tokenService.hashToken("value-one");
        String hash2 = tokenService.hashToken("value-two");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void generateRefreshToken_isUniqueEachCall() {
        String rt1 = tokenService.generateRefreshToken();
        String rt2 = tokenService.generateRefreshToken();

        assertThat(rt1).isNotEqualTo(rt2);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User buildUser(UUID id, UUID company, String email, UserRole role) {
        User u = new User();
        u.setId(id);
        u.setCompanyId(company);
        u.setEmail(email);
        u.setRole(role);
        u.setFullName("Test User");
        return u;
    }
}
