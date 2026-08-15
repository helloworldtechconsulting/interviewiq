package com.interviewiq.shared.config;

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
    private String fromAddress = "noreply@interviewiq.in";

    /** Display name on outbound mail. */
    private String fromName = "InterviewIQ";

    /**
     * Where bounce and complaint callbacks are expected to arrive.
     *
     * <p>They feed the suppression list (§13.3). Recorded here so the value the
     * provider is configured with and the value the application expects cannot
     * drift apart unnoticed.
     */
    private String bounceCallbackPath = "/api/v1/webhooks/email";

    /** When true, sends are logged instead of transmitted. Local development only. */
    private boolean useLocalStub = false;

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }

    public String getBounceCallbackPath() { return bounceCallbackPath; }
    public void setBounceCallbackPath(String bounceCallbackPath) { this.bounceCallbackPath = bounceCallbackPath; }

    public boolean isUseLocalStub() { return useLocalStub; }
    public void setUseLocalStub(boolean useLocalStub) { this.useLocalStub = useLocalStub; }
}
