package com.interviewiq.email.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewiq.email.domain.DeliveryNotification;
import com.interviewiq.email.service.DeliveryNotificationParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resend webhook shape.
 *
 * <pre>
 * {
 *   "type": "email.bounced",
 *   "data": {
 *     "email_id": "...",
 *     "to": ["candidate@example.com"],
 *     "bounce": { "type": "Permanent", "message": "..." }
 *   }
 * }
 * </pre>
 *
 * <p>Resend reports bounce hardness in {@code data.bounce.type}, using SES's
 * vocabulary: {@code Permanent}, {@code Transient}, {@code Undetermined}.
 * {@code Undetermined} is treated as soft — guessing "permanent" on an
 * ambiguous signal would suppress an address that may be perfectly live, and
 * that error is much worse than sending one more email that bounces.
 */
@Component
public class ResendNotificationParser implements DeliveryNotificationParser {

    @Override
    public String providerKey() {
        return "resend";
    }

    @Override
    public List<DeliveryNotification> parse(JsonNode body) {
        String type    = body.path("type").asText("").toLowerCase(Locale.ROOT);
        JsonNode data  = body.path("data");
        String eventId = data.path("email_id").asText("");

        DeliveryNotification.Kind kind = switch (type) {
            case "email.bounced"   -> bounceKind(data.path("bounce").path("type").asText(""));
            case "email.complained" -> DeliveryNotification.Kind.COMPLAINT;
            default                -> DeliveryNotification.Kind.IGNORED;
        };

        String detail = data.path("bounce").path("message").asText(type);

        List<DeliveryNotification> out = new ArrayList<>();
        for (JsonNode recipient : data.path("to")) {
            String email = recipient.asText("").trim().toLowerCase(Locale.ROOT);
            if (!email.isBlank()) {
                // Suffix the recipient so a batch delivered to several addresses
                // does not collapse into one idempotency key and lose all but
                // the first suppression.
                out.add(new DeliveryNotification(email, kind, eventId + ":" + email, detail));
            }
        }
        return out;
    }

    private DeliveryNotification.Kind bounceKind(String bounceType) {
        return "permanent".equalsIgnoreCase(bounceType)
                ? DeliveryNotification.Kind.HARD_BOUNCE
                : DeliveryNotification.Kind.SOFT_BOUNCE;
    }
}
