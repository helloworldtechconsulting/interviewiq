package com.interviewengine.webhook.domain;

/**
 * Sources of inbound webhook deliveries.
 *
 * <p>DB CHECK values: {@code 'RAZORPAY'}, {@code 'SYSTEM'} (see V040).
 *
 * <p>{@code RECALL_AI} was removed with the March design. All media now runs in
 * the candidate's browser, so there is no meeting bot to call back — the
 * equivalent signal arrives on {@code POST /candidate/sessions/{id}/events},
 * which the candidate's own browser calls (PRD v2.1 §11).
 */
public enum WebhookProvider {
    /** Razorpay payment events — HMAC-SHA256 verified, idempotent on payment ID. */
    RAZORPAY,
    /** Internally generated events, and SMTP-provider bounce/complaint callbacks. */
    SYSTEM
}
