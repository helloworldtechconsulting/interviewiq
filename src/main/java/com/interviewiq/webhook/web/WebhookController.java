package com.interviewiq.webhook.web;

import com.interviewiq.webhook.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound webhook receiver.
 *
 * <p>Recall.ai webhook endpoint ({@code POST /api/v1/webhooks/recall}) was removed
 * in V038. Session lifecycle events are now driven by the browser directly via
 * {@code /api/v1/candidate/interview/*} REST endpoints.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
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
}
