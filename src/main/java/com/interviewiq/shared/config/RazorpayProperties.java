package com.interviewiq.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay payment gateway credentials.
 *
 * <p>Bound to the {@code app.razorpay} namespace.
 *
 * <p>In production, inject via environment variables:
 * {@code APP_RAZORPAY_KEY_ID} and {@code APP_RAZORPAY_KEY_SECRET}.
 *
 * <p>Webhook signature verification uses {@code keySecret} as the HMAC key.
 * Never expose the secret in logs or API responses.
 */
@ConfigurationProperties(prefix = "app.razorpay")
public class RazorpayProperties {

    /** Razorpay publishable key (safe to share with the frontend). */
    private String keyId = "";

    /** Razorpay secret key — used for order creation and webhook HMAC verification. */
    private String keySecret = "";

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
}
