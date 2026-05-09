package com.interviewiq.webhook;

import com.interviewiq.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook signature-verification IT.
 *
 * <p>Exercises the {@code /api/v1/webhooks/razorpay} endpoint:
 * <ul>
 *   <li>An HMAC-SHA256 signature derived from the configured key-secret is
 *       accepted (200). The orderId in the payload won't match any wallet,
 *       so the service short-circuits but the signature path still passes.</li>
 *   <li>A wrong signature is rejected (400 — {@code ValidationException} maps
 *       to BAD_REQUEST in {@code GlobalExceptionHandler}).</li>
 * </ul>
 *
 * <p>Wallet-credit-on-valid + idempotent-replay assertions require a fully
 * onboarded company + wallet and a matching Razorpay order; covered in
 * {@link com.interviewiq.billing.service.WalletServiceTest} at the unit level.
 */
class WebhookIT extends AbstractIntegrationTest {

    @Value("${app.razorpay.key-secret}")
    private String razorpaySecret;

    private static final String PAYLOAD =
            "{\"event\":\"payment.captured\"," +
            "\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_test_unmatched\"," +
            "\"order_id\":\"order_test_unmatched\",\"amount\":50000}}}}";

    @Test
    @DisplayName("Valid HMAC-SHA256 signature is accepted (200)")
    void razorpayWebhook_validHmac_returnsOk() throws Exception {
        String signature = hmacSha256(PAYLOAD, razorpaySecret);

        mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", signature)
                        .content(PAYLOAD))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Invalid HMAC signature is rejected (400)")
    void razorpayWebhook_invalidHmac_returnsClientError() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "deadbeef")
                        .content(PAYLOAD))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status < 400 || status >= 500) {
                        throw new AssertionError(
                                "Expected 4xx for bad signature, got " + status);
                    }
                });
    }

    private static String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
