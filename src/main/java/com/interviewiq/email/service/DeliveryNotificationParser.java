package com.interviewiq.email.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewiq.email.domain.DeliveryNotification;

import java.util.List;

/**
 * Translates one SMTP provider's webhook JSON into {@link DeliveryNotification}s.
 *
 * <p>One implementation per provider named in Architecture v4.0 §3. Which one
 * is active is a configuration value ({@code app.mail.webhook-provider}), the
 * same way the SMTP transport itself is — swapping provider must not be a code
 * change anywhere but here.
 *
 * <p>Returns a list because some providers batch several events into one POST.
 * Most send exactly one; the list costs nothing and removes the assumption.
 */
public interface DeliveryNotificationParser {

    /** Value of {@code app.mail.webhook-provider} that selects this parser. */
    String providerKey();

    /**
     * Parses a verified request body.
     *
     * <p>Implementations must not throw on an unrecognised event type — new
     * event types get added by providers without notice, and a parser that
     * throws turns "we received an open-tracking event we don't care about"
     * into a 500 and a provider-side retry loop. Return
     * {@link DeliveryNotification.Kind#IGNORED} instead.
     */
    List<DeliveryNotification> parse(JsonNode body);
}
