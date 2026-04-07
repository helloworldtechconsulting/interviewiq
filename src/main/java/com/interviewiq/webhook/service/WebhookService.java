package com.interviewiq.webhook.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.service.SessionService;
import com.interviewiq.shared.config.RazorpayProperties;
import com.interviewiq.shared.config.RecallProperties;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.webhook.domain.WebhookEvent;
import com.interviewiq.webhook.domain.WebhookProvider;
import com.interviewiq.webhook.infrastructure.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Processes inbound webhooks from Razorpay and Recall.ai.
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
 * <h2>Recall.ai events handled</h2>
 * <ul>
 *   <li>{@code bot.joined}    — record Recall bot ID on the session</li>
 *   <li>{@code bot.done}      — mark session COMPLETED, trigger settlement</li>
 *   <li>{@code bot.failed}    — mark session ERROR</li>
 * </ul>
 *
 * <h2>Signature verification</h2>
 * <p>Razorpay: HMAC-SHA256 of raw body with {@code razorpayKeySecret}.
 * Recall.ai: HMAC-SHA256 of raw body with {@code recallWebhookSecret}.
 * Both use the {@code X-*-Signature} HTTP header.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookEventRepository webhookEventRepository;
    private final WalletService          walletService;
    private final SessionService         sessionService;
    private final RazorpayProperties     razorpayProps;
    private final RecallProperties       recallProps;
    private final ObjectMapper           objectMapper;

    @Value("${app.billing.session-cost-paise:5000}")
    private long sessionCostPaise;

    public WebhookService(WebhookEventRepository webhookEventRepository,
                          WalletService walletService,
                          SessionService sessionService,
                          RazorpayProperties razorpayProps,
                          RecallProperties recallProps,
                          ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.walletService          = walletService;
        this.sessionService         = sessionService;
        this.razorpayProps          = razorpayProps;
        this.recallProps            = recallProps;
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

        if ("payment.captured".equals(eventType)) {
            processRazorpayPaymentCaptured(root, event);
        } else {
            log.debug("Unhandled Razorpay event type: {}", eventType);
        }

        markProcessed(event);
    }

    // =========================================================================
    // Recall.ai
    // =========================================================================

    /**
     * Receives and processes a Recall.ai webhook after signature verification.
     */
    @Transactional
    public void handleRecall(byte[] rawBody, String signatureHeader) {
        verifyHmac(rawBody, signatureHeader, recallProps.getWebhookSecret(), "Recall.ai");

        String payloadJson = new String(rawBody, StandardCharsets.UTF_8);
        JsonNode root = parseJson(payloadJson);

        String eventType     = root.path("event").asText();
        String botId         = root.path("data").path("bot_id").asText("");
        String idempotencyKey = root.path("id").asText(botId + "-" + eventType);

        if (isDuplicate(WebhookProvider.RECALL_AI, idempotencyKey)) {
            log.info("Duplicate Recall.ai event ignored: key={}", idempotencyKey);
            return;
        }

        WebhookEvent event = persist(WebhookProvider.RECALL_AI, eventType, payloadJson, idempotencyKey, null);

        switch (eventType) {
            case "bot.joined"  -> processRecallBotJoined(root, botId, event);
            case "bot.done"    -> processRecallBotDone(root, botId, event);
            case "bot.failed"  -> processRecallBotFailed(root, botId, event);
            default            -> log.debug("Unhandled Recall.ai event type: {}", eventType);
        }

        markProcessed(event);
    }

    // =========================================================================
    // Razorpay handlers
    // =========================================================================

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

    // =========================================================================
    // Recall.ai handlers
    // =========================================================================

    private void processRecallBotJoined(JsonNode root, String botId, WebhookEvent event) {
        UUID sessionId = resolveSessionFromBot(root);
        if (sessionId == null) return;

        InterviewSession session = sessionService.requireSessionById(sessionId);
        session.setRecallBotId(botId);
        if (session.getStatus() == SessionStatus.INVITED) {
            session.setStatus(SessionStatus.STARTED);
            session.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        sessionService.save(session);
        event.setCompanyId(session.getCompanyId());
        log.info("Recall bot joined: sessionId={} botId={}", sessionId, botId);
    }

    private void processRecallBotDone(JsonNode root, String botId, WebhookEvent event) {
        UUID sessionId = resolveSessionFromBot(root);
        if (sessionId == null) return;

        InterviewSession session = sessionService.requireSessionById(sessionId);
        if (session.getStatus() == SessionStatus.STARTED) {
            session.setStatus(SessionStatus.COMPLETED);
            session.setEndedAt(OffsetDateTime.now(ZoneOffset.UTC));
            sessionService.save(session);

            // Settle billing — charge the full reservation
            walletService.settleFunds(session.getCompanyId(), sessionId, sessionCostPaise);
        }
        event.setCompanyId(session.getCompanyId());
        log.info("Recall bot done: sessionId={}", sessionId);
    }

    private void processRecallBotFailed(JsonNode root, String botId, WebhookEvent event) {
        UUID sessionId = resolveSessionFromBot(root);
        if (sessionId == null) return;

        InterviewSession session = sessionService.requireSessionById(sessionId);
        session.setStatus(SessionStatus.ERROR);
        session.setErrorCode("BOT_FAILED");
        session.setErrorMessage(root.path("data").path("error").asText("Unknown Recall.ai error"));
        session.setEndedAt(OffsetDateTime.now(ZoneOffset.UTC));
        sessionService.save(session);

        // Release the billing reservation on failure
        walletService.releaseFunds(session.getCompanyId(), sessionId);
        event.setCompanyId(session.getCompanyId());
        log.warn("Recall bot failed: sessionId={} botId={}", sessionId, botId);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private UUID resolveSessionFromBot(JsonNode root) {
        String sessionIdStr = root.path("data").path("metadata").path("session_id").asText("");
        if (sessionIdStr.isBlank()) {
            log.warn("Recall.ai event missing session_id metadata");
            return null;
        }
        try {
            return UUID.fromString(sessionIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid session_id in Recall.ai metadata: {}", sessionIdStr);
            return null;
        }
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
     * @throws ValidationException if the signature does not match
     */
    private void verifyHmac(byte[] body, String signature, String secret, String provider) {
        if (secret == null || secret.isBlank()) {
            // Stub mode: skip verification when secret is not configured
            log.debug("[WEBHOOK STUB] Skipping {} signature verification (secret not configured)", provider);
            return;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(body));
            if (!expected.equalsIgnoreCase(signature)) {
                log.warn("{} webhook signature mismatch", provider);
                throw new ValidationException("Invalid webhook signature.");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC verification setup failed", e);
        }
    }
}
