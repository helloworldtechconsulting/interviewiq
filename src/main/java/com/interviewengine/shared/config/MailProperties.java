package com.interviewengine.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound email configuration (PRD v2.1 §13.3; Arch v4.0 §3).
 *
 * <p>Bound to {@code app.mail}. The SMTP host, port and credentials themselves
 * live under Spring's own {@code spring.mail.*}, which {@code JavaMailSender}
 * consumes; this covers what the application decides rather than what the
 * transport needs.
 *
 * <p>SES was replaced by SMTP in v2.1 to make the provider a configuration value
 * — Resend, Brevo, Postmark or SES-over-SMTP. The trade is stated plainly in the
 * PRD: portable SMTP providers cost more than SES for the same deliverability,
 * and that is an accepted cost of portability.
 *
 * <p>Whichever provider is chosen, domain verification with DKIM, SPF and DMARC
 * is a launch-blocking task (§13.3).
 */
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

    /** Envelope sender. Must be on a domain verified with the SMTP provider. */
    private String fromAddress = "noreply@interviewengine.ai";

    /** Display name on outbound mail. */
    private String fromName = "InterviewEngine";

    /**
     * Where bounce and complaint callbacks are expected to arrive.
     *
     * <p>They feed the suppression list (§13.3). Recorded here so the value the
     * provider is configured with and the value the application expects cannot
     * drift apart unnoticed.
     */
    private String bounceCallbackPath = "/api/v1/webhooks/email";

    /**
     * Which provider's webhook payload shape to expect — {@code resend},
     * {@code postmark} or {@code brevo} (Arch v4.0 §3).
     *
     * <p>Deliberately has no default. Guessing the provider would mean parsing
     * a bounce with the wrong adapter, which does not fail loudly: the fields
     * simply come back empty and every notification is silently classified as
     * {@code IGNORED}. A blank value rejects the webhook outright instead, which
     * is noticeable on day one rather than at the first deliverability crisis.
     */
    private String webhookProvider = "";

    /**
     * Shared secret for verifying inbound bounce/complaint callbacks.
     *
     * <p>Must be set in any environment where the callback is reachable. The
     * verifier fails closed on a blank secret — see
     * {@code WebhookSignatureVerifier}.
     */
    private String webhookSecret = "";

    /** Header the provider puts its HMAC signature in. */
    private String webhookSignatureHeader = "X-Webhook-Signature";

    /** When true, sends are logged instead of transmitted. Local development only. */
    private boolean useLocalStub = false;

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }

    public String getBounceCallbackPath() { return bounceCallbackPath; }
    public void setBounceCallbackPath(String bounceCallbackPath) { this.bounceCallbackPath = bounceCallbackPath; }

    public String getWebhookProvider() { return webhookProvider; }
    public void setWebhookProvider(String webhookProvider) { this.webhookProvider = webhookProvider; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String getWebhookSignatureHeader() { return webhookSignatureHeader; }
    public void setWebhookSignatureHeader(String webhookSignatureHeader) { this.webhookSignatureHeader = webhookSignatureHeader; }

    public boolean isUseLocalStub() { return useLocalStub; }
    public void setUseLocalStub(boolean useLocalStub) { this.useLocalStub = useLocalStub; }
}
