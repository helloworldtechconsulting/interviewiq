package com.interviewiq.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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

    public Jwt          getJwt()          { return jwt; }
    public Invite       getInvite()       { return invite; }
    public Google       getGoogle()       { return google; }
    public LoginAttempt getLoginAttempt() { return loginAttempt; }

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

    // =========================================================================
    // Google OAuth (ID-token verification)
    // =========================================================================

    public static class Google {

        /**
         * OAuth 2.0 Client ID from Google Cloud Console.
         * Used by {@code GoogleOAuthService} to verify the {@code aud} claim
         * in incoming Google ID tokens.
         * In production: inject as {@code APP_SECURITY_GOOGLE_CLIENT_ID}.
         */
        private String clientId = "";

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
    }

    // =========================================================================
    // Login-attempt lockout (per-IP failed-login tracking)
    // =========================================================================

    public static class LoginAttempt {

        /**
         * Number of failed login attempts within the window that triggers lockout.
         * Defaults to 5 per requirement: "5 failed attempts/IP/min → 15-min lockout".
         */
        private int maxFailures = 5;

        /**
         * Sliding window in which failures are counted before lockout.
         * Defaults to 1 minute.
         */
        private Duration windowDuration = Duration.ofMinutes(1);

        /**
         * How long the IP is locked out after hitting {@code maxFailures}.
         * Defaults to 15 minutes.
         */
        private Duration lockoutDuration = Duration.ofMinutes(15);

        public int getMaxFailures() { return maxFailures; }
        public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }

        public Duration getWindowDuration() { return windowDuration; }
        public void setWindowDuration(Duration windowDuration) { this.windowDuration = windowDuration; }

        public Duration getLockoutDuration() { return lockoutDuration; }
        public void setLockoutDuration(Duration lockoutDuration) { this.lockoutDuration = lockoutDuration; }
    }
}
