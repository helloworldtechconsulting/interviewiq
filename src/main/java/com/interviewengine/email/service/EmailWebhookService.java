package com.interviewengine.email.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewengine.email.domain.DeliveryNotification;
import com.interviewengine.email.domain.EmailEvent;
import com.interviewengine.email.domain.EmailStatus;
import com.interviewengine.email.infrastructure.EmailEventRepository;
import com.interviewengine.shared.config.MailProperties;
import com.interviewengine.shared.exception.ValidationException;
import com.interviewengine.webhook.domain.WebhookEvent;
import com.interviewengine.webhook.domain.WebhookProvider;
import com.interviewengine.webhook.infrastructure.WebhookEventRepository;
import com.interviewengine.webhook.service.WebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Inbound SMTP bounce and complaint callbacks (INTIQ-32, PRD v2.1 §13.3).
 *
 * <p>{@code POST /api/v1/webhooks/email}. The provider posts a delivery
 * outcome; we verify it, normalise it, and — for hard bounces and complaints —
 * stop sending to that address.
 *
 * <h2>Why this endpoint is worth building before launch volume</h2>
 *
 * <p>The story that carried it was filed as "matters only at volume", and for
 * the suppression list itself that is roughly true. The part that does not wait
 * is the signal: without this, a candidate whose invite bounced looks
 * identical to a candidate who received it and ignored it. The employer chases
 * a no-show that never happened, and the ₹100 reservation sits against an
 * interview that was never reachable.
 */
@Service
public class EmailWebhookService {

    private static final Logger log = LoggerFactory.getLogger(EmailWebhookService.class);

    private final WebhookEventRepository         webhookEventRepository;
    private final EmailEventRepository           emailEventRepository;
    private final EmailSuppressionService        suppressionService;
    private final WebhookSignatureVerifier       signatureVerifier;
    private final MailProperties                 mailProperties;
    private final ObjectMapper                   objectMapper;
    private final Map<String, DeliveryNotificationParser> parsers;

    public EmailWebhookService(WebhookEventRepository webhookEventRepository,
                               EmailEventRepository emailEventRepository,
                               EmailSuppressionService suppressionService,
                               WebhookSignatureVerifier signatureVerifier,
                               MailProperties mailProperties,
                               ObjectMapper objectMapper,
                               List<DeliveryNotificationParser> parserBeans) {
        this.webhookEventRepository = webhookEventRepository;
        this.emailEventRepository   = emailEventRepository;
        this.suppressionService     = suppressionService;
        this.signatureVerifier      = signatureVerifier;
        this.mailProperties         = mailProperties;
        this.objectMapper           = objectMapper;
        this.parsers = parserBeans.stream().collect(
                Collectors.toMap(p -> p.providerKey().toLowerCase(Locale.ROOT), Function.identity()));
    }

    /**
     * Verifies, records and acts on one provider callback.
     *
     * @param rawBody   raw request body — signature is over these exact bytes,
     *                  so it must not be re-serialised before verification
     * @param signature value of the configured signature header
     */
    @Transactional
    public void handle(byte[] rawBody, String signature) {
        DeliveryNotificationParser parser = requireParser();

        signatureVerifier.verify(rawBody, signature, mailProperties.getWebhookSecret(), "SMTP");

        String payloadJson = new String(rawBody, StandardCharsets.UTF_8);
        JsonNode root = parseJson(payloadJson);

        List<DeliveryNotification> notifications = parser.parse(root);
        if (notifications.isEmpty()) {
            log.debug("SMTP webhook carried no recognisable recipient — ignoring");
            return;
        }

        for (DeliveryNotification notification : notifications) {
            process(notification, payloadJson);
        }
    }

    private void process(DeliveryNotification notification, String payloadJson) {
        String idempotencyKey = notification.providerEventId();

        if (webhookEventRepository.existsByProviderAndIdempotencyKey(WebhookProvider.SYSTEM, idempotencyKey)) {
            log.debug("Duplicate SMTP notification ignored: key={}", idempotencyKey);
            return;
        }

        WebhookEvent event = new WebhookEvent();
        event.setProvider(WebhookProvider.SYSTEM);
        event.setEventType("email." + notification.kind().name().toLowerCase(Locale.ROOT));
        event.setPayloadJson(payloadJson);
        event.setIdempotencyKey(idempotencyKey);
        event.setProcessed(false);
        webhookEventRepository.save(event);

        switch (notification.kind()) {
            case HARD_BOUNCE, COMPLAINT -> {
                suppressionService.suppress(
                        notification.email(),
                        notification.suppressionReason(),
                        notification.providerEventId(),
                        notification.detail());
                markLatestSendBounced(notification);
                log.warn("Address suppressed from SMTP callback: email={} kind={} detail={}",
                        notification.email(), notification.kind(), notification.detail());
            }
            case SOFT_BOUNCE -> {
                // Recorded, not suppressed. A full mailbox on Monday is a
                // deliverable address on Friday.
                markLatestSendBounced(notification);
                log.info("Soft bounce, address left deliverable: email={} detail={}",
                        notification.email(), notification.detail());
            }
            case IGNORED -> log.debug("SMTP event not acted on: email={}", notification.email());
        }

        event.setProcessed(true);
        event.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        webhookEventRepository.save(event);
    }

    /**
     * Marks the most recent successful send to this address as BOUNCED.
     *
     * <p>Only rows already in {@code SENT} are eligible: the {@code sent_at}
     * consistency constraint requires a timestamp for BOUNCED, and a row that
     * never reached SENT does not have one. Best-effort by design — if no
     * matching send is found the suppression still stands, which is the part
     * that protects deliverability.
     */
    private void markLatestSendBounced(DeliveryNotification notification) {
        List<EmailEvent> recent = emailEventRepository
                .findTop10ByRecipientEmailAndStatusOrderByCreatedAtDesc(
                        notification.email(), EmailStatus.SENT);

        if (recent.isEmpty()) {
            log.debug("No SENT email found to attribute bounce to: {}", notification.email());
            return;
        }

        EmailEvent latest = recent.get(0);
        latest.setStatus(EmailStatus.BOUNCED);
        emailEventRepository.save(latest);
    }

    private DeliveryNotificationParser requireParser() {
        String key = mailProperties.getWebhookProvider();
        if (key == null || key.isBlank()) {
            log.error("SMTP webhook rejected: app.mail.webhook-provider is not set");
            throw new ValidationException("Email webhook provider is not configured.");
        }
        DeliveryNotificationParser parser = parsers.get(key.toLowerCase(Locale.ROOT));
        if (parser == null) {
            log.error("SMTP webhook rejected: no parser for provider '{}'. Known: {}", key, parsers.keySet());
            throw new ValidationException("Email webhook provider is not supported: " + key);
        }
        return parser;
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON webhook payload.");
        }
    }
}
