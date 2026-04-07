-- =============================================================================
-- V012__webhook_create_webhook_events_table.sql
-- depends on: V001__company_create_companies_table.sql
-- =============================================================================

CREATE TABLE webhook_events (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),

    -- nullable: some webhooks arrive before company resolution is complete
    -- (e.g. a Razorpay event for a payment whose company context is resolved
    -- asynchronously by the WebhookProcessorService)
    company_id      UUID,

    provider        VARCHAR(50)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload_json    JSONB        NOT NULL,

    -- provider-assigned or application-constructed ID used to deduplicate
    -- redeliveries. Construction format: {PROVIDER}:{provider_event_id}
    -- Examples: RAZORPAY:pay_xyz123, RECALL_AI:evt_abc456
    idempotency_key VARCHAR(255) NOT NULL,

    processed       BOOLEAN      NOT NULL DEFAULT FALSE,
    processed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_webhook_events
        PRIMARY KEY (id),

    -- ON DELETE SET NULL: company deletion must not destroy webhook audit history
    CONSTRAINT fk_webhook_events_companies
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE SET NULL,

    -- deduplicates redeliveries within a provider namespace.
    -- scoped to (provider, idempotency_key) because providers only guarantee
    -- uniqueness within their own ID space — the same key value can legitimately
    -- appear across different providers.
    CONSTRAINT uq_webhook_events_provider_idempotency_key
        UNIQUE (provider, idempotency_key),

    -- RAZORPAY  — payment events (payment.captured, payment.failed, refund.created)
    -- RECALL_AI — bot lifecycle events (bot.joined, bot.left, bot.recording_done)
    -- SYSTEM    — internal platform events routed through the webhook infrastructure
    CONSTRAINT ck_webhook_events_provider_valid
        CHECK (provider IN ('RAZORPAY', 'RECALL_AI', 'SYSTEM')),

    -- processed and processed_at must move together:
    -- unprocessed → processed_at must be NULL
    -- processed   → processed_at must be set
    CONSTRAINT ck_webhook_events_processed_consistency
        CHECK (
            (processed = FALSE AND processed_at IS NULL)
            OR
            (processed = TRUE AND processed_at IS NOT NULL)
        ),

    -- processing cannot be recorded as happening before the event was received
    CONSTRAINT ck_webhook_events_processed_at_after_created
        CHECK (processed_at IS NULL OR processed_at >= created_at)
);
