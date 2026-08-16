package com.interviewengine.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewengine.billing.service.WalletService;
import com.interviewengine.shared.config.RazorpayProperties;
import com.interviewengine.shared.exception.ValidationException;
import com.interviewengine.webhook.domain.WebhookEvent;
import com.interviewengine.webhook.domain.WebhookProvider;
import com.interviewengine.webhook.infrastructure.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WebhookService}.
 *
 * <p>Focuses on the two security-critical behaviours:
 * <ol>
 *   <li>HMAC signature verification — valid, invalid, and stub (blank secret) cases.</li>
 *   <li>Idempotency — duplicate events are detected and silently discarded.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock WebhookEventRepository webhookEventRepository;
    @Mock WalletService          walletService;
    @Mock RazorpayProperties     razorpayProps;

    private WebhookService webhookService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String RAZORPAY_SECRET = "test-razorpay-secret-32-bytes-ok";

    @BeforeEach
    void setUp() {
        // Real verifier, not a mock — these tests assert on signature rejection,
        // and a mocked verifier would make them pass without verifying anything.
        webhookService = new WebhookService(
                webhookEventRepository, walletService,
                razorpayProps, objectMapper, new WebhookSignatureVerifier());
    }

    // =========================================================================
    // HMAC verification — Razorpay
    // =========================================================================

    @Test
    void handleRazorpay_acceptsRequest_whenSignatureIsValid() throws Exception {
        byte[] body = minimalRazorpayPayload("payment.captured", "pay_001", "order_001");
        String validSig = hmacSha256Hex(body, RAZORPAY_SECRET);

        when(razorpayProps.getKeySecret()).thenReturn(RAZORPAY_SECRET);
        when(webhookEventRepository.existsByProviderAndIdempotencyKey(
                eq(WebhookProvider.RAZORPAY), any())).thenReturn(false);
        when(webhookEventRepository.save(any())).thenAnswer(inv -> {
            WebhookEvent e = inv.getArgument(0);
            e.setId(java.util.UUID.randomUUID());
            return e;
        });

        // Should not throw — wallet call will fail with mock but the sig check passes
        assertThatCode(() -> webhookService.handleRazorpay(body, validSig))
                .doesNotThrowAnyException();
    }

    @Test
    void handleRazorpay_throwsValidationException_whenSignatureMismatch() {
        byte[] body = minimalRazorpayPayload("payment.captured", "pay_001", "order_001");
        when(razorpayProps.getKeySecret()).thenReturn(RAZORPAY_SECRET);

        assertThatThrownBy(() -> webhookService.handleRazorpay(body, "wrong-signature"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("signature");
    }

    /**
     * A blank signing secret must reject the request, not wave it through.
     *
     * <p>The webhook endpoint is {@code permitAll}. If an unconfigured secret meant
     * "skip verification", anyone could POST a forged {@code payment.captured} and
     * mint unlimited wallet credit — which is why PRD v2.1 §7.1.3 requires
     * verification to fail closed and names it a launch blocker.
     */
    @Test
    void handleRazorpay_rejects_whenSecretIsNotConfigured() {
        byte[] body = minimalRazorpayPayload("payment.captured", "pay_002", "order_002");
        when(razorpayProps.getKeySecret()).thenReturn("");

        assertThatThrownBy(() -> webhookService.handleRazorpay(body, "any-signature"))
                .isInstanceOf(ValidationException.class);

        // Nothing may be persisted and no credit may be granted on an unverified event.
        verify(webhookEventRepository, never()).save(any());
        verifyNoInteractions(walletService);
    }

    @Test
    void handleRazorpay_rejects_whenSignatureHeaderIsAbsent() {
        byte[] body = minimalRazorpayPayload("payment.captured", "pay_003", "order_003");
        when(razorpayProps.getKeySecret()).thenReturn(RAZORPAY_SECRET);

        assertThatThrownBy(() -> webhookService.handleRazorpay(body, null))
                .isInstanceOf(ValidationException.class);

        verify(webhookEventRepository, never()).save(any());
        verifyNoInteractions(walletService);
    }

    @Test
    void handleRazorpay_rejects_whenSignatureIsNotHex() {
        byte[] body = minimalRazorpayPayload("payment.captured", "pay_004", "order_004");
        when(razorpayProps.getKeySecret()).thenReturn(RAZORPAY_SECRET);

        assertThatThrownBy(() -> webhookService.handleRazorpay(body, "not-a-hex-digest!!"))
                .isInstanceOf(ValidationException.class);

        verify(webhookEventRepository, never()).save(any());
        verifyNoInteractions(walletService);
    }

    // =========================================================================
    // Idempotency — duplicate event detection
    // =========================================================================

    @Test
    void handleRazorpay_silentlyIgnores_whenDuplicateIdempotencyKey() {
        byte[] body     = minimalRazorpayPayload("payment.captured", "pay_dup", "order_dup");
        String validSig = hmacSha256Hex(body, RAZORPAY_SECRET);
        when(razorpayProps.getKeySecret()).thenReturn(RAZORPAY_SECRET);
        when(webhookEventRepository.existsByProviderAndIdempotencyKey(
                eq(WebhookProvider.RAZORPAY), any())).thenReturn(true);  // already processed

        webhookService.handleRazorpay(body, validSig);

        // Must not persist a second event or call WalletService
        verify(webhookEventRepository, never()).save(any());
        verifyNoInteractions(walletService);
    }

    // =========================================================================
    // Invalid JSON body
    // =========================================================================

    @Test
    void handleRazorpay_throwsValidationException_onMalformedJson() {
        byte[] malformed = "not-json".getBytes(StandardCharsets.UTF_8);
        String sig       = hmacSha256Hex(malformed, RAZORPAY_SECRET);
        when(razorpayProps.getKeySecret()).thenReturn(RAZORPAY_SECRET);

        assertThatThrownBy(() -> webhookService.handleRazorpay(malformed, sig))
                .isInstanceOf(ValidationException.class);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a minimal Razorpay {@code payment.captured} payload JSON as bytes.
     * The {@code event} field drives the handler switch; the nested payment/order
     * fields supply the idempotency key and amount.
     */
    private byte[] minimalRazorpayPayload(String event, String paymentId, String orderId) {
        String json = """
                {
                  "event": "%s",
                  "payload": {
                    "payment": { "entity": { "id": "%s", "order_id": "%s", "amount": 20000 } },
                    "order":   { "entity": { "receipt": "topup-00000000-0000-0000-0000-000000000001" } }
                  }
                }
                """.formatted(event, paymentId, orderId);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Computes the HMAC-SHA256 hex digest used by Razorpay signature verification.
     */
    private String hmacSha256Hex(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
