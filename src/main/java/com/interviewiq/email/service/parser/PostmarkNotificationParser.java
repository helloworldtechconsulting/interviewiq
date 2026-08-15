package com.interviewiq.email.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewiq.email.domain.DeliveryNotification;
import com.interviewiq.email.service.DeliveryNotificationParser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Postmark webhook shape.
 *
 * <pre>
 * { "RecordType": "Bounce", "Type": "HardBounce", "Email": "x@example.com",
 *   "ID": 42, "Description": "..." }
 * { "RecordType": "SpamComplaint", "Email": "x@example.com", "ID": 43 }
 * </pre>
 *
 * <p>Postmark enumerates bounce types by name rather than by hardness, so the
 * mapping is explicit. The default is soft: an unrecognised bounce type is far
 * more likely to be a new transient condition than a new permanent one, and
 * erring toward soft costs one wasted send while erring toward hard silently
 * cuts a candidate off.
 */
@Component
public class PostmarkNotificationParser implements DeliveryNotificationParser {

    @Override
    public String providerKey() {
        return "postmark";
    }

    @Override
    public List<DeliveryNotification> parse(JsonNode body) {
        String recordType = body.path("RecordType").asText("").toLowerCase(Locale.ROOT);
        String email      = body.path("Email").asText("").trim().toLowerCase(Locale.ROOT);
        String eventId    = body.path("ID").asText("");
        String detail     = body.path("Description").asText(recordType);

        DeliveryNotification.Kind kind = switch (recordType) {
            case "bounce"        -> bounceKind(body.path("Type").asText(""));
            case "spamcomplaint" -> DeliveryNotification.Kind.COMPLAINT;
            default              -> DeliveryNotification.Kind.IGNORED;
        };

        if (email.isBlank()) {
            return List.of();
        }
        return List.of(new DeliveryNotification(email, kind, eventId + ":" + email, detail));
    }

    private DeliveryNotification.Kind bounceKind(String type) {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "hardbounce", "bademailaddress", "manuallydeactivated", "blocked" ->
                    DeliveryNotification.Kind.HARD_BOUNCE;
            default -> DeliveryNotification.Kind.SOFT_BOUNCE;
        };
    }
}
