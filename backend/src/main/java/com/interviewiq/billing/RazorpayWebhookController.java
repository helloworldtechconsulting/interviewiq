package com.interviewiq.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class RazorpayWebhookController {

    private final BillingService billingService;
    private final ObjectMapper objectMapper;

    @Value("${razorpay.webhook-secret:}")
    private String webhookSecret;

    @PostMapping("/razorpay")
    public ResponseEntity<ApiResponse<String>> handleRazorpayWebhook(
            @RequestHeader("X-Razorpay-Signature") String signature,
            @RequestBody String payload) {

        try {
            // Verify HMAC-SHA256 signature
            if (!verifySignature(payload, signature)) {
                log.warn("Invalid Razorpay webhook signature");
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Invalid signature", "Webhook signature verification failed"));
            }

            JsonNode webhookData = objectMapper.readTree(payload);
            String event = webhookData.get("event").asText();

            if ("payment.captured".equals(event)) {
                handlePaymentCaptured(webhookData);
            } else {
                log.info("Ignoring webhook event: {}", event);
            }

            return ResponseEntity.ok(ApiResponse.success("Webhook processed", "Razorpay webhook handled successfully"));
        } catch (Exception e) {
            log.error("Error processing Razorpay webhook: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Processing failed", "Error processing webhook: " + e.getMessage()));
        }
    }

    private void handlePaymentCaptured(JsonNode webhookData) throws Exception {
        JsonNode payload = webhookData.get("payload").get("payment").get("entity");

        String paymentId = payload.get("id").asText();
        long amountPaise = payload.get("amount").asLong();
        String orderId = payload.get("order_id").asText();
        JsonNode notesNode = payload.get("notes");

        // Extract company ID from notes (should be stored when initiating payment)
        UUID companyId = null;
        if (notesNode != null && notesNode.has("company_id")) {
            companyId = UUID.fromString(notesNode.get("company_id").asText());
        }

        if (companyId != null) {
            log.info("Payment captured: {} for order: {} amount: {} paise", paymentId, orderId, amountPaise);
            billingService.creditWallet(companyId, amountPaise, paymentId);
        } else {
            log.warn("Could not find company_id in payment notes for order: {}", orderId);
        }
    }

    private boolean verifySignature(String payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String calculatedSignature = Base64.getEncoder().encodeToString(hash);
            return calculatedSignature.equals(signature);
        } catch (Exception e) {
            log.error("Error verifying webhook signature: {}", e.getMessage());
            return false;
        }
    }
}
