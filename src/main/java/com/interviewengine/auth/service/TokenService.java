package com.interviewengine.auth.service;

import com.interviewengine.auth.config.SecurityProperties;
import com.interviewengine.auth.domain.User;
import com.interviewengine.auth.domain.UserRole;
import com.interviewengine.auth.exception.InvalidTokenException;
import com.interviewengine.auth.exception.TokenExpiredException;
import com.interviewengine.auth.service.dto.AccessTokenClaims;
import com.interviewengine.auth.service.dto.InviteTokenClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.PublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Central service for creating and validating all token types used by InterviewEngine.
 *
 * <h2>Access tokens (RS256 JWT)</h2>
 * <ul>
 *   <li>Signed with the RSA private key; verified with the public key.</li>
 *   <li>Claims: {@code sub} = userId, {@code cid} = companyId, {@code email},
 *       {@code role}, {@code type = "ACCESS"}.</li>
 *   <li>No database lookup on validation — all required principal data is in
 *       the token itself.</li>
 * </ul>
 *
 * <h2>Refresh tokens</h2>
 * <ul>
 *   <li>A random {@link UUID} is the raw token value returned to the client.</li>
 *   <li>Only the SHA-256 hash of the raw value is stored in {@code refresh_tokens}.</li>
 *   <li>{@link #hashToken(String)} converts raw → hash for both storage and lookup.</li>
 * </ul>
 *
 * <h2>Invite tokens (HS256 JWT)</h2>
 * <ul>
 *   <li>Signed with a shared HMAC-SHA256 key; stateless (no DB lookup on validation).</li>
 *   <li>Claims: {@code sub} = sessionId, {@code cid} = companyId,
 *       {@code canId} = candidateId, {@code type = "INVITE"}.</li>
 *   <li>The session-state guard (session must be {@code INVITED}) is enforced in
 *       the filter, not here.</li>
 * </ul>
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    // JWT claim keys
    public static final String CLAIM_COMPANY_ID  = "cid";
    public static final String CLAIM_EMAIL       = "email";
    public static final String CLAIM_ROLE        = "role";
    public static final String CLAIM_CANDIDATE_ID = "canId";
    public static final String CLAIM_TOKEN_TYPE  = "type";

    private static final String TYPE_ACCESS      = "ACCESS";
    private static final String TYPE_INVITE      = "INVITE";

    private final KeyPair          jwtKeyPair;
    private final SecretKey        inviteHmacKey;
    private final SecurityProperties props;

    public TokenService(KeyPair jwtKeyPair,
                        SecretKey inviteHmacKey,
                        SecurityProperties props) {
        this.jwtKeyPair    = jwtKeyPair;
        this.inviteHmacKey = inviteHmacKey;
        this.props         = props;
    }

    // =========================================================================
    // Access token
    // =========================================================================

    /**
     * Generates a signed RS256 access token for the given employer user.
     *
     * @param user the authenticated employer user
     * @return compact JWT string (safe to return in API responses)
     */
    public String generateAccessToken(User user) {
        Instant now    = Instant.now();
        Instant expiry = now.plus(props.getJwt().getAccessTokenExpiration());

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_COMPANY_ID,  user.getCompanyId().toString())
                .claim(CLAIM_EMAIL,       user.getEmail())
                .claim(CLAIM_ROLE,        user.getRole().name())
                .claim(CLAIM_TOKEN_TYPE,  TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(jwtKeyPair.getPrivate())
                .compact();
    }

    /**
     * Validates a JWT access token and returns its decoded claims.
     *
     * @param token the compact JWT string from the Authorization header
     * @return decoded, validated claims — never null
     * @throws TokenExpiredException  if the token's {@code exp} claim is in the past
     * @throws InvalidTokenException  if the signature is wrong, claims are missing,
     *                                or the token type is not {@code ACCESS}
     */
    public AccessTokenClaims validateAccessToken(String token) {
        Claims claims = parseJwt(token, (PublicKey) jwtKeyPair.getPublic());

        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            throw new InvalidTokenException("Token is not a valid access token");
        }

        try {
            return new AccessTokenClaims(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(CLAIM_COMPANY_ID, String.class)),
                    claims.get(CLAIM_EMAIL, String.class),
                    UserRole.valueOf(claims.get(CLAIM_ROLE, String.class))
            );
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Access token contains malformed claims");
        }
    }

    // =========================================================================
    // Refresh token
    // =========================================================================

    /**
     * Generates a new raw refresh token (a random UUID string).
     * The caller is responsible for hashing the value before storing it.
     *
     * @return raw token string — store only the SHA-256 hash, return this to the client
     */
    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * Returns the SHA-256 hex-encoded hash of a raw token value.
     * Used for both storage ({@code refresh_tokens.token_hash}) and lookup.
     *
     * @param rawToken the raw token string (refresh token UUID or invite token)
     * @return lowercase hex-encoded SHA-256 hash
     */
    public String hashToken(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // =========================================================================
    // Invite token
    // =========================================================================

    /**
     * Generates a signed HS256 invite token for a candidate.
     *
     * @param sessionId   the interview session the candidate is invited to
     * @param candidateId the candidate's UUID
     * @param companyId   the company that owns the session
     * @return compact JWT string suitable for embedding in an email link
     */
    public String generateInviteToken(UUID sessionId, UUID candidateId, UUID companyId) {
        Instant now    = Instant.now();
        Instant expiry = now.plus(props.getInvite().getExpiration());

        return Jwts.builder()
                .subject(sessionId.toString())
                .claim(CLAIM_COMPANY_ID,   companyId.toString())
                .claim(CLAIM_CANDIDATE_ID, candidateId.toString())
                .claim(CLAIM_TOKEN_TYPE,   TYPE_INVITE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(inviteHmacKey)
                .compact();
    }

    /**
     * Validates a candidate invite token and returns its decoded claims.
     *
     * @param token the compact JWT invite token
     * @return decoded, validated claims — never null
     * @throws TokenExpiredException if the invite link has expired
     * @throws InvalidTokenException if the signature fails or claims are malformed
     */
    public InviteTokenClaims validateInviteToken(String token) {
        Claims claims = parseJwt(token, inviteHmacKey);

        if (!TYPE_INVITE.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            throw new InvalidTokenException("Token is not a valid invite token");
        }

        try {
            return new InviteTokenClaims(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(CLAIM_CANDIDATE_ID, String.class)),
                    UUID.fromString(claims.get(CLAIM_COMPANY_ID, String.class))
            );
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Invite token contains malformed claims");
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Parses and verifies a JWT using an RSA public key (RS256).
     * Translates JJWT exceptions into application-layer exceptions with safe messages.
     */
    private Claims parseJwt(String token, PublicKey verificationKey) {
        try {
            return Jwts.parser()
                    .verifyWith(verificationKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("Token has expired");
        } catch (JwtException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            throw new InvalidTokenException("Token is invalid or has been tampered with");
        }
    }

    /**
     * Parses and verifies a JWT using a symmetric HMAC key (HS256).
     */
    private Claims parseJwt(String token, SecretKey verificationKey) {
        try {
            return Jwts.parser()
                    .verifyWith(verificationKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("Token has expired");
        } catch (JwtException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            throw new InvalidTokenException("Token is invalid or has been tampered with");
        }
    }
}
