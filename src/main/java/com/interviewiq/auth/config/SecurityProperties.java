package com.interviewiq.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Strongly-typed configuration for all security concerns:
 * JWT signing keys, token lifetimes, and invite-token secrets.
 *
 * <p>Bound to the {@code app.security} namespace in YAML / environment variables.
 *
 * <p>Key management rules:
 * <ul>
 *   <li>If {@code app.security.jwt.private-key-pem} is empty, an ephemeral RSA key pair
 *       is generated at startup. This is acceptable for local development but MUST NOT
 *       be used in production (tokens become invalid on every restart).</li>
 *   <li>If {@code app.security.invite.secret} is empty, an ephemeral HMAC key is
 *       generated. Same constraint applies.</li>
 *   <li>In production, inject both PEM values and the invite secret as environment
 *       variables: {@code APP_SECURITY_JWT_PRIVATE_KEY_PEM},
 *       {@code APP_SECURITY_JWT_PUBLIC_KEY_PEM}, {@code APP_SECURITY_INVITE_SECRET}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private final Jwt          jwt          = new Jwt();
    private final Invite       invite       = new Invite();
    private final Google       google       = new Google();
    private final LoginAttempt loginAttempt = new LoginAttempt();
    private final Cors         cors         = new Cors();

    public Jwt          getJwt()          { return jwt; }
    public Invite       getInvite()       { return invite; }
    public Google       getGoogle()       { return google; }
    public LoginAttempt getLoginAttempt() { return loginAttempt; }
    public Cors         getCors()         { return cors; }

    // =========================================================================
    // JWT (RS256 asymmetric — employer access tokens)
    // =========================================================================

    public static class Jwt {

        /**
         * PKCS#8 PEM-encoded RSA private key (the {@code -----BEGIN PRIVATE KEY-----}
         * format). Used to sign access tokens. Empty triggers ephemeral key generation.
         * In production: inject as {@code APP_SECURITY_JWT_PRIVATE_KEY_PEM}.
         */
        private String privateKeyPem = "";

        /**
         * X.509 PEM-encoded RSA public key (the {@code -----BEGIN PUBLIC KEY-----}
         * format). Used to verify access tokens. Must match the private key above.
         * In production: inject as {@code APP_SECURITY_JWT_PUBLIC_KEY_PEM}.
         */
        private String publicKeyPem = "";

        /**
         * How long access tokens remain valid. Defaults to 15 minutes.
         * Override in local profile to a longer duration for development convenience.
         */
        private Duration accessTokenExpiration = Duration.ofMinutes(15);

        /**
         * How long refresh tokens remain valid. Defaults to 30 days.
         */
        private Duration refreshTokenExpiration = Duration.ofDays(30);

        public String getPrivateKeyPem() { return privateKeyPem; }
        public void setPrivateKeyPem(String privateKeyPem) { this.privateKeyPem = privateKeyPem; }

        public String getPublicKeyPem() { return publicKeyPem; }
        public void setPublicKeyPem(String publicKeyPem) { this.publicKeyPem = publicKeyPem; }

        public Duration getAccessTokenExpiration() { return accessTokenExpiration; }
        public void setAccessTokenExpiration(Duration d) { this.accessTokenExpiration = d; }

        public Duration getRefreshTokenExpiration() { return refreshTokenExpiration; }
        public void setRefreshTokenExpiration(Duration d) { this.refreshTokenExpiration = d; }
    }

    // =========================================================================
    // Invite token (HS256 symmetric — candidate invite links)
    // =========================================================================

    public static class Invite {

        /**
         * UTF-8 string used as the seed for the HMAC-SHA256 signing key.
         * Must be at least 32 characters long. Empty triggers ephemeral key generation.
         * In production: inject as {@code APP_SECURITY_INVITE_SECRET}.
         */
        private String secret = "";

        /**
         * How long invite tokens remain valid after issuance. Defaults to 7 days.
         */
        private Duration expiration = Duration.ofDays(7);

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }

        public Duration getExpiration() { return expiration; }
        public void setExpiration(Duration expiration) { this.expiration = expiration; }
    }

    public static class Google {

        private String clientId = "";

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
    }

    // =========================================================================
    // CORS (PRD v2.1 §7.1.3 — a permissive or absent policy is a launch blocker)
    // =========================================================================

    /**
     * Cross-origin policy for the browser SPA and the candidate interview room.
     *
     * <p>PRD v2.1 §7.1.3 requires CORS to be <em>restricted to the configured
     * frontend origins</em>. There is deliberately no wildcard support: origins
     * are an explicit allow-list, and an empty list means no cross-origin request
     * is permitted at all. Credentials are allowed because the refresh token
     * travels in an HTTP-only cookie, and the CORS spec forbids pairing
     * {@code allowCredentials} with an {@code *} origin — so the allow-list is a
     * correctness requirement, not only a hardening one.
     */
    public static class Cors {

        /**
         * Exact origins permitted to call the API, e.g.
         * {@code https://app.interviewiq.in}. Scheme, host and port must all match.
         * In production: inject as {@code APP_SECURITY_CORS_ALLOWED_ORIGINS}.
         */
        private List<String> allowedOrigins = new ArrayList<>();

        /** HTTP methods permitted cross-origin. */
        private List<String> allowedMethods =
                new ArrayList<>(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        /** Request headers permitted cross-origin. */
        private List<String> allowedHeaders =
                new ArrayList<>(List.of("Authorization", "Content-Type", "X-Requested-With"));

        /** Response headers exposed to the browser. */
        private List<String> exposedHeaders = new ArrayList<>();

        /** How long a browser may cache the preflight response. */
        private Duration maxAge = Duration.ofHours(1);

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }

        public List<String> getAllowedMethods() { return allowedMethods; }
        public void setAllowedMethods(List<String> allowedMethods) { this.allowedMethods = allowedMethods; }

        public List<String> getAllowedHeaders() { return allowedHeaders; }
        public void setAllowedHeaders(List<String> allowedHeaders) { this.allowedHeaders = allowedHeaders; }

        public List<String> getExposedHeaders() { return exposedHeaders; }
        public void setExposedHeaders(List<String> exposedHeaders) { this.exposedHeaders = exposedHeaders; }

        public Duration getMaxAge() { return maxAge; }
        public void setMaxAge(Duration maxAge) { this.maxAge = maxAge; }
    }

    public static class LoginAttempt {

        private int maxFailures = 5;

        private Duration windowDuration = Duration.ofMinutes(1);

        private Duration lockoutDuration = Duration.ofMinutes(15);

        public int getMaxFailures() { return maxFailures; }
        public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }

        public Duration getWindowDuration() { return windowDuration; }
        public void setWindowDuration(Duration windowDuration) { this.windowDuration = windowDuration; }

        public Duration getLockoutDuration() { return lockoutDuration; }
        public void setLockoutDuration(Duration lockoutDuration) { this.lockoutDuration = lockoutDuration; }
    }
}
