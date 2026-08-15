package com.interviewiq.webhook.service;

import com.interviewiq.shared.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * HMAC-SHA256 verification for inbound webhooks.
 *
 * <p>Extracted from {@link WebhookService} when a second webhook (SMTP
 * bounce/complaint, INTIQ-32) needed the same guarantee. Two private copies of
 * a fail-closed security check is one copy too many — the failure mode of
 * divergence is that the newer one quietly grows a "verification not
 * configured, allow it through" branch.
 */
@Component
public class WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);

    /** How the provider encodes the signature it puts in the header. */
    public enum Encoding { HEX, BASE64 }

    /**
     * Verifies an HMAC-SHA256 signature over the raw request body.
     *
     * <p><strong>Fails closed.</strong> A missing or blank signing secret, and a
     * missing or blank signature, are both rejected — neither is treated as
     * "verification not configured, carry on". Webhook endpoints are
     * {@code permitAll}, so skipping verification whenever the secret happens to
     * be unset would let anyone forge {@code payment.captured} and mint
     * unlimited wallet credit, or forge a complaint and suppress a competitor's
     * address. PRD v2.1 §7.1.3 names this as a launch blocker.
     *
     * <p>The comparison is constant-time, so a caller cannot recover a valid
     * signature byte by byte from response timing.
     *
     * @throws ValidationException if the secret is absent, the signature is
     *                             absent, malformed, or does not match
     */
    public void verify(byte[] body, String signature, String secret, String provider, Encoding encoding) {
        if (secret == null || secret.isBlank()) {
            log.error("{} webhook rejected: signing secret is not configured", provider);
            throw new ValidationException("Webhook signature verification is not configured.");
        }
        if (signature == null || signature.isBlank()) {
            log.warn("{} webhook rejected: request carried no signature", provider);
            throw new ValidationException("Invalid webhook signature.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);

            byte[] provided = decode(signature.trim(), encoding, provider);

            if (!MessageDigest.isEqual(expected, provided)) {
                log.warn("{} webhook signature mismatch", provider);
                throw new ValidationException("Invalid webhook signature.");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC verification setup failed", e);
        }
    }

    /** Convenience overload for the common hex-encoded case. */
    public void verify(byte[] body, String signature, String secret, String provider) {
        verify(body, signature, secret, provider, Encoding.HEX);
    }

    private byte[] decode(String signature, Encoding encoding, String provider) {
        try {
            return switch (encoding) {
                case HEX    -> HexFormat.of().parseHex(signature.toLowerCase(Locale.ROOT));
                case BASE64 -> Base64.getDecoder().decode(signature);
            };
        } catch (IllegalArgumentException e) {
            log.warn("{} webhook rejected: signature is not valid {}", provider, encoding);
            throw new ValidationException("Invalid webhook signature.");
        }
    }
}
