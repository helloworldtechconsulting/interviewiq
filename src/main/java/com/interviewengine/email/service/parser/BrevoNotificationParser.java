package com.interviewengine.email.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewengine.email.domain.DeliveryNotification;
import com.interviewengine.email.service.DeliveryNotificationParser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Brevo (formerly Sendinblue) webhook shape.
 *
 * <pre>
 * { "event": "hard_bounce", "email": "x@example.com",
 *   "message-id": "&lt;...&gt;", "reason": "..." }
 * </pre>
 *
 * <p>Brevo names hardness directly in the event, which makes this the simplest
 * of the three mappings. {@code blocked} is included as a hard outcome: Brevo
 * emits it when the address is already on its own internal blocklist, which
 * means further sends will not be attempted regardless of what we decide.
 */
@Component
public class BrevoNotificationParser implements DeliveryNotificationParser {

    @Override
    public String providerKey() {
        return "brevo";
    }

    @Override
    public List<DeliveryNotification> parse(JsonNode body) {
        String event   = body.path("event").asText("").toLowerCase(Locale.ROOT);
        String email   = body.path("email").asText("").trim().toLowerCase(Locale.ROOT);
        String eventId = body.path("message-id").asText("");
        String detail  = body.path("reason").asText(event);

        DeliveryNotification.Kind kind = switch (event) {
            case "hard_bounce", "blocked", "invalid_email" -> DeliveryNotification.Kind.HARD_BOUNCE;
            case "soft_bounce", "deferred"                 -> DeliveryNotification.Kind.SOFT_BOUNCE;
            case "spam", "complaint"                       -> DeliveryNotification.Kind.COMPLAINT;
            default                                        -> DeliveryNotification.Kind.IGNORED;
        };

        if (email.isBlank()) {
            return List.of();
        }
        return List.of(new DeliveryNotification(email, kind, eventId + ":" + email, detail));
    }
}
