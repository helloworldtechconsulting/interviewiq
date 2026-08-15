package com.interviewiq.webhook.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.shared.config.RazorpayProperties;
import com.interviewiq.webhook.domain.WebhookEvent;
import com.interviewiq.webhook.domain.WebhookProvider;
import com.interviewiq.webhook.infrastructure.WebhookEventRepository;
import com.interviewiq.shared.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Processes inbound webhooks from Razorpay.
 *
 * <p>Recall.ai webhook handling was removed in V038 as part of the migration
 * to an in-browser WebRTC interview room (PRD v3). All session lifecycle events
 * are now driven by the browser via {@code /api/v1/candidate/interview/*} REST endpoints.
 *
 * <h2>Idempotency</h2>
 * <p>Each event is persisted with a unique {@code (provider, idempotencyKey)} before
 * processing. Duplicate deliveries are silently ignored at the persistence layer.
 *
 * <h2>Razorpay events handled</h2>
 * <ul>
 *   <li>{@code payment.captured} — credit wallet via {@link WalletService#confirmTopUp}</li>
 * </ul>
 *
 * <h2>Signature verification</h2>
 * <p>Razorpay: HMAC-SHA256 of raw body with {@code razorpayKeySecret}.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventRepository webhookEventRepository;
    private final WalletService          walletService;
    private final RazorpayProperties     razorpayProps;
    private final ObjectMapper           objectMapper;

    public WebhookService(WebhookEventRepository webhookEventRepository,
                          WalletService walletService,
                          RazorpayProperties razorpayProps,
                          ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.walletService          = walletService;
        this.razorpayProps          = razorpayProps;
        this.objectMapper           = objectMapper;
    }

    // =========================================================================
    // Razorpay
    // =========================================================================

    /**
     * Receives and processes a Razorpay webhook after signature verification.
     *
     * @param rawBody        raw HTTP request body bytes
     * @param signatureHeader value of the {@code X-Razorpay-Signature} header
     */
    @Transactional
    public void handleRazorpay(byte[] rawBody, String signatureHeader) {
        verifyHmac(rawBody, signatureHeader, razorpayProps.getKeySecret(), "Razorpay");

        String payloadJson = new String(rawBody, StandardCharsets.UTF_8);
        JsonNode root = parseJson(payloadJson);

        String eventType     = root.path("event").asText();
        String idempotencyKey = root.path("payload").path("payment").path("entity").path("id").asText("");
        if (idempotencyKey.isBlank()) {
            idempotencyKey = root.path("id").asText(UUID.randomUUID().toString());
        }

        if (isDuplicate(WebhookProvider.RAZORPAY, idempotencyKey)) {
            log.info("Duplicate Razorpay event ignored: key={}", idempotencyKey);
            return;
        }

        WebhookEvent event = persist(WebhookProvider.RAZORPAY, eventType, payloadJson, idempotencyKey, null);

        switch (eventType == null ? "" : eventType) {
            case "payment.captured" -> processRazorpayPaymentCaptured(root, event);
            case "payment.failed"   -> processRazorpayPaymentFailed(root, event);
            default -> log.debug("Unhandled Razorpay event type: {}", eventType);
        }

        markProcessed(event);
    }

    private void processRazorpayPaymentCaptured(JsonNode root, WebhookEvent event) {
        JsonNode payment  = root.path("payload").path("payment").path("entity");
        String  orderId   = payment.path("order_id").asText();
        long    amount    = payment.path("amount").asLong();
        String  receiptId = root.path("payload").path("order").path("entity").path("receipt").asText("");

        // Receipt format: "topup-{companyId}"
        UUID companyId = null;
        if (receiptId.startsWith("topup-")) {
            try {
                companyId = UUID.fromString(receiptId.substring(6));
            } catch (IllegalArgumentException e) {
                log.warn("Could not parse companyId from receipt: {}", receiptId);
            }
        }

        if (companyId == null) {
            log.error("Cannot credit wallet: missing companyId in payment.captured event orderId={}", orderId);
            return;
        }

        event.setCompanyId(companyId);
        walletService.confirmTopUp(orderId, amount, companyId);
        log.info("Razorpay payment captured: orderId={} companyId={} amount={}", orderId, companyId, amount);
    }

    /**
     * Records a failed payment attempt (INTIQ-71).
     *
     * <p><strong>No wallet mutation.</strong> A failed payment never created a
     * credit, so there is nothing to reverse — treating this as a debit would be
     * the actual bug. The value of handling the event at all is diagnostic: when
     * a customer says "I paid and my balance did not change", the answer is
     * either "your bank declined it, here is the reason code" or "we have a
     * problem", and without this row there is no way to tell which.
     *
     * <p>The failure reason is stored on the webhook event rather than emailed to
     * the customer. Razorpay already tells them at the point of failure, in the
     * checkout flow, with better context than we have — a second message from us
     * minutes later would add confusion, not clarity.
     */
    private void processRazorpayPaymentFailed(JsonNode root, WebhookEvent event) {
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String orderId     = payment.path("order_id").asText("");
        String errorCode   = payment.path("error_code").asText("");
        String errorReason = payment.path("error_description").asText("");
        String receiptId   = root.path("payload").path("order").path("entity").path("receipt").asText("");

        if (receiptId.startsWith("topup-")) {
            try {
                event.setCompanyId(UUID.fromString(receiptId.substring(6)));
            } catch (IllegalArgumentException e) {
                log.warn("Could not parse companyId from failed-payment receipt: {}", receiptId);
            }
        }

        log.warn("Razorpay payment failed: orderId={} companyId={} code={} reason={}",
                orderId, event.getCompanyId(), errorCode, errorReason);
    }

    private boolean isDuplicate(WebhookProvider provider, String idempotencyKey) {
        return webhookEventRepository.existsByProviderAndIdempotencyKey(provider, idempotencyKey);
    }

    private WebhookEvent persist(WebhookProvider provider, String eventType,
                                  String payloadJson, String idempotencyKey, UUID companyId) {
        WebhookEvent event = new WebhookEvent();
        event.setProvider(provider);
        event.setEventType(eventType);
        event.setPayloadJson(payloadJson);
        event.setIdempotencyKey(idempotencyKey);
        event.setCompanyId(companyId);
        event.setProcessed(false);
        return webhookEventRepository.save(event);
    }

    private void markProcessed(WebhookEvent event) {
        event.setProcessed(true);
        event.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        webhookEventRepository.save(event);
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON webhook payload.");
        }
    }

    /**
     * Verifies an HMAC-SHA256 signature over the raw request body.
     *
     * <p><strong>This method fails closed.</strong> A missing or blank signing
     * secret, and a missing or blank signature, are both rejected — they are not
     * treated as "verification not configured". The webhook endpoint is
     * {@code permitAll}, so skipping verification when the secret happens to be
     * unset lets anyone forge {@code payment.captured} and mint unlimited wallet
     * credit. PRD v2.1 §7.1.3 names this as a launch blocker.
     *
     * <p>The digest comparison is constant-time so that a caller cannot recover a
     * valid signature byte by byte from response timing.
     *
     * @throws ValidationException if the secret is absent, the signature is
     *                             absent, or the signature does not match
     */
    private void verifyHmac(byte[] body, String signature, String secret, String provider) {
        if (secret == null || secret.isBlank()) {
            log.error("{} webhook rejected: signing secret is not configured", provider);
            throw new ValidationException("Webhook signature verification is not configured.");
        }
        if (signature == null || signature.isBlank()) {
            log.warn("{} webhook rejected: request carried no signature", provider);
            throw new ValidationException("Invalid webhook signature.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);

            byte[] provided;
            try {
                provided = HexFormat.of().parseHex(signature.trim().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warn("{} webhook rejected: signature is not valid hex", provider);
                throw new ValidationException("Invalid webhook signature.");
            }

            if (!MessageDigest.isEqual(expected, provided)) {
                log.warn("{} webhook signature mismatch", provider);
                throw new ValidationException("Invalid webhook signature.");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC verification setup failed", e);
        }
    }
}
