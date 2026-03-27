package com.interviewiq.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component
@Slf4j
public class JwtUtil {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiry-minutes:60}")
    private int expiryMinutes;

    @Value("${app.jwt.refresh-expiry-days:7}")
    private int refreshExpiryDays;

    @Value("${app.jwt.issuer:interviewiq}")
    private String issuer;

    private static final String CLAIMS_USER_ID = "userId";
    private static final String CLAIMS_COMPANY_ID = "companyId";
    private static final String CLAIMS_ROLE = "role";

    public String generateAccessToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIMS_USER_ID, user.getId().toString());
        claims.put(CLAIMS_COMPANY_ID, user.getCompanyId().toString());
        claims.put(CLAIMS_ROLE, user.getRole().name());
        return createToken(claims, user.getEmail(), expiryMinutes * 60 * 1000);
    }

    public String generateRefreshToken(User user) {
        return createToken(new HashMap<>(), user.getEmail(), refreshExpiryDays * 24 * 60 * 60 * 1000);
    }

    public String generateInviteToken(String email, UUID sessionId, long expiryMillis) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sessionId", sessionId.toString());
        claims.put("type", "invite");
        return createToken(claims, email, expiryMillis);
    }

    private String createToken(Map<String, Object> claims, String subject, long expiryMillis) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiryMillis);

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiryDate)
                .issuer(issuer)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public UUID extractUserId(String token) {
        String userId = extractClaim(token, claims -> (String) claims.get(CLAIMS_USER_ID));
        return userId != null ? UUID.fromString(userId) : null;
    }

    public UUID extractCompanyId(String token) {
        String companyId = extractClaim(token, claims -> (String) claims.get(CLAIMS_COMPANY_ID));
        return companyId != null ? UUID.fromString(companyId) : null;
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> (String) claims.get(CLAIMS_ROLE));
    }

    public UUID extractSessionId(String token) {
        String sessionId = extractClaim(token, claims -> (String) claims.get("sessionId"));
        return sessionId != null ? UUID.fromString(sessionId) : null;
    }

    public boolean isTokenValid(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            log.warn("Invalid token: {}", e.getMessage());
            return false;
        }
    }

    public boolean isTokenExpired(String token) {
        try {
            Date expiration = extractClaim(token, Claims::getExpiration);
            return expiration.before(new Date());
        } catch (Exception e) {
            log.warn("Error checking token expiration: {}", e.getMessage());
            return true;
        }
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes();
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
