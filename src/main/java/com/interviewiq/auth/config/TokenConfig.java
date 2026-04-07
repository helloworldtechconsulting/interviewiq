package com.interviewiq.auth.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Produces the cryptographic key beans consumed by {@link com.interviewiq.auth.service.TokenService}.
 *
 * <h2>JWT key pair (RS256)</h2>
 * <p>If {@code app.security.jwt.private-key-pem} is empty (local development), an
 * ephemeral 2048-bit RSA key pair is generated at startup.  A WARN log makes this
 * visible so the condition is never silently promoted to staging.
 *
 * <p>In production both PEM values must be supplied via environment variables
 * {@code APP_SECURITY_JWT_PRIVATE_KEY_PEM} and {@code APP_SECURITY_JWT_PUBLIC_KEY_PEM}.
 * The private key must be in PKCS#8 format ({@code -----BEGIN PRIVATE KEY-----});
 * the public key in X.509/SPKI format ({@code -----BEGIN PUBLIC KEY-----}).
 *
 * <h2>Invite HMAC key (HS256)</h2>
 * <p>If {@code app.security.invite.secret} is empty, an ephemeral HS256 key is
 * generated.  In production inject {@code APP_SECURITY_INVITE_SECRET} (min 32 chars).
 */
@Configuration
public class TokenConfig {

    private static final Logger log = LoggerFactory.getLogger(TokenConfig.class);

    // =========================================================================
    // JWT RSA Key Pair
    // =========================================================================

    @Bean
    public KeyPair jwtKeyPair(SecurityProperties props) {
        String privatePem = props.getJwt().getPrivateKeyPem();
        String publicPem  = props.getJwt().getPublicKeyPem();

        if (privatePem == null || privatePem.isBlank()) {
            log.warn("⚠ No RSA key pair configured (app.security.jwt.private-key-pem is empty). " +
                     "Generating ephemeral keys — tokens will be invalid after restart. " +
                     "NEVER use this in staging or production.");
            return Jwts.SIG.RS256.keyPair().build();
        }

        try {
            RSAPrivateKey privateKey = parsePkcs8PrivateKey(privatePem);
            RSAPublicKey  publicKey  = parseX509PublicKey(publicPem);
            log.info("JWT RSA key pair loaded from configuration.");
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to load RSA key pair from app.security.jwt PEM configuration", e);
        }
    }

    // =========================================================================
    // Invite HMAC Key
    // =========================================================================

    @Bean
    public SecretKey inviteHmacKey(SecurityProperties props) {
        String secret = props.getInvite().getSecret();

        if (secret == null || secret.isBlank()) {
            log.warn("⚠ No invite secret configured (app.security.invite.secret is empty). " +
                     "Generating ephemeral HMAC key — invite links will be invalid after restart. " +
                     "NEVER use this in staging or production.");
            return Jwts.SIG.HS256.key().build();
        }

        if (secret.length() < 32) {
            throw new IllegalStateException(
                "app.security.invite.secret must be at least 32 characters long");
        }

        // SHA-256 the secret to always produce exactly 256 bits regardless of input length
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = md.digest(secret.getBytes(StandardCharsets.UTF_8));
            log.info("Invite HMAC key derived from configured secret.");
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // =========================================================================
    // PEM parsing helpers
    // =========================================================================

    /**
     * Parses a PKCS#8 PEM private key (the {@code -----BEGIN PRIVATE KEY-----} format).
     * Uses Java standard library only — no Bouncy Castle dependency required.
     */
    private RSAPrivateKey parsePkcs8PrivateKey(String pem) throws Exception {
        byte[] keyBytes = decodePem(pem, "PRIVATE KEY");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    /**
     * Parses an X.509/SPKI PEM public key (the {@code -----BEGIN PUBLIC KEY-----} format).
     */
    private RSAPublicKey parseX509PublicKey(String pem) throws Exception {
        byte[] keyBytes = decodePem(pem, "PUBLIC KEY");
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    /**
     * Strips PEM header/footer lines and Base64-decodes the body.
     *
     * @param pem   the full PEM string (may contain newlines)
     * @param label the expected label (e.g. "PRIVATE KEY", "PUBLIC KEY")
     */
    private byte[] decodePem(String pem, String label) {
        String cleaned = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(cleaned);
    }
}
