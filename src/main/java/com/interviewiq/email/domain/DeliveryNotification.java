package com.interviewiq.email.domain;

/**
 * One delivery outcome reported by the SMTP provider, normalised away from
 * whichever provider's JSON shape it arrived in (INTIQ-32).
 *
 * <p>Architecture v4.0 §3 makes the email provider a configuration value —
 * Resend, Brevo, Postmark or SES-over-SMTP — so the provider's payload format
 * is exactly the kind of detail that must not reach the rest of the
 * application. Everything past the parser works in this type.
 *
 * @param email           recipient, lowercased
 * @param kind            what the provider is telling us happened
 * @param providerEventId the provider's own ID for this notification, used as
 *                        the idempotency key — providers retry on non-2xx and
 *                        will happily deliver the same bounce several times
 * @param detail          human-readable reason, kept for support triage
 */
public record DeliveryNotification(
        String email,
        Kind   kind,
        String providerEventId,
        String detail
) {

    public enum Kind {

        /**
         * The address does not exist or has permanently refused mail.
         * Suppresses: continuing to send damages sender reputation for every
         * other recipient on the domain.
         */
        HARD_BOUNCE,

        /**
         * Temporary — mailbox full, greylisted, server down.
         * Does <em>not</em> suppress. A full mailbox on Monday is a deliverable
         * address on Friday, and suppressing on a soft bounce would silently
         * cut off candidates for a condition that resolves itself.
         */
        SOFT_BOUNCE,

        /**
         * The recipient marked it as spam. Suppresses immediately and
         * regardless of volume — one complaint is a clear instruction, and
         * providers weigh complaint rate far more heavily than bounce rate.
         */
        COMPLAINT,

        /**
         * A delivery, open, click, or anything else we do not act on. Recorded
         * as a webhook event and otherwise dropped.
         */
        IGNORED
    }

    /** True when this outcome should put the address on the suppression list. */
    public boolean suppresses() {
        return kind == Kind.HARD_BOUNCE || kind == Kind.COMPLAINT;
    }

    /** The suppression reason this outcome maps to. */
    public SuppressionReason suppressionReason() {
        return kind == Kind.COMPLAINT ? SuppressionReason.COMPLAINT : SuppressionReason.BOUNCE;
    }
}
