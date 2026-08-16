package com.interviewiq.webhook.web;

import com.interviewiq.email.service.EmailWebhookService;
import com.interviewiq.shared.config.MailProperties;
import com.interviewiq.webhook.service.WebhookService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final WebhookService      webhookService;
    private final EmailWebhookService emailWebhookService;
    private final MailProperties      mailProperties;

    public WebhookController(WebhookService webhookService,
                             EmailWebhookService emailWebhookService,
                             MailProperties mailProperties) {
        this.webhookService      = webhookService;
        this.emailWebhookService = emailWebhookService;
        this.mailProperties      = mailProperties;
    }

    /**
     * Razorpay webhook — payment.captured credits wallet.
     * Signature header: X-Razorpay-Signature
     * Permit-all in SecurityConfig.
     */
    @PostMapping("/razorpay")
    public ResponseEntity<Void> razorpay(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", defaultValue = "") String signature) {
        webhookService.handleRazorpay(rawBody, signature);
        return ResponseEntity.ok().build();
    }

    /**
     * SMTP provider bounce and complaint callbacks (INTIQ-32).
     *
     * <p>Hard bounces and spam complaints add the address to the suppression
     * list; soft bounces are recorded and nothing more. Permit-all in
     * SecurityConfig and HMAC-verified in the service — see
     * {@code WebhookSignatureVerifier}, which fails closed.
     *
     * <p>The signature header name is read from configuration rather than
     * declared as a fixed {@code @RequestHeader}, because it differs per
     * provider and Architecture v4.0 §3 makes the provider a config value.
     * {@link HttpHeaders} lookup is case-insensitive, which matters — providers
     * are not consistent about header casing.
     */
    @PostMapping("/email")
    public ResponseEntity<Void> email(@RequestBody byte[] rawBody,
                                      @RequestHeader HttpHeaders headers) {
        String signature = headers.getFirst(mailProperties.getWebhookSignatureHeader());
        emailWebhookService.handle(rawBody, signature == null ? "" : signature);
        return ResponseEntity.ok().build();
    }
}
