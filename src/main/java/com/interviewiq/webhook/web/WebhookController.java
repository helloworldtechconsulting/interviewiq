package com.interviewiq.webhook.web;

import com.interviewiq.webhook.service.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

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
     * Recall.ai webhook — bot lifecycle events update session state.
     * Signature header: X-Recall-Hmac-Signature
     * Permit-all in SecurityConfig.
     */
    @PostMapping("/recall")
    public ResponseEntity<Void> recall(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Recall-Hmac-Signature", defaultValue = "") String signature) {
        webhookService.handleRecall(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
