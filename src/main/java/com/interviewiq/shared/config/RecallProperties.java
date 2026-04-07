package com.interviewiq.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Recall.ai API credentials and configuration.
 *
 * <p>Bound to the {@code app.recall} namespace.
 *
 * <p>In production, inject via environment variables:
 * {@code APP_RECALL_API_KEY} and {@code APP_RECALL_WEBHOOK_SECRET}.
 */
@ConfigurationProperties(prefix = "app.recall")
public class RecallProperties {

    /** Recall.ai REST API key. Used in the {@code Authorization} header. */
    private String apiKey = "";

    /** HMAC-SHA256 secret for verifying Recall.ai webhook signatures. */
    private String webhookSecret = "";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
