-- =============================================================================
-- V015__email_create_email_events_table.sql
-- depends on: V001__company_create_companies_table.sql
--             V002__auth_create_users_table.sql
-- =============================================================================

CREATE TABLE email_events (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),

    -- nullable: some emails are sent before company/user context is established
    -- (e.g. first-contact invite); SET NULL preserves delivery history on deletion
    company_id          UUID,
    user_id             UUID,

    recipient_email     VARCHAR(255) NOT NULL,
    email_type          VARCHAR(100) NOT NULL,

    -- provider-assigned message ID (e.g. SES MessageId, SendGrid X-Message-Id)
    -- used to correlate delivery webhooks back to this record
    provider_message_id VARCHAR(255),

    status              VARCHAR(50)  NOT NULL DEFAULT 'QUEUED',
    payload_json        JSONB,
    sent_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_email_events
        PRIMARY KEY (id),

    -- SET NULL: company/user deletion must not destroy email delivery history
    CONSTRAINT fk_email_events_companies
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE SET NULL,

    CONSTRAINT fk_email_events_users
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,

    -- provider_message_id must be globally unique when present:
    -- bounce/delivery webhooks reference this ID to update a specific row;
    -- a duplicate would cause two email records to match the same webhook event.
    -- PostgreSQL UNIQUE constraints permit multiple NULL values — unqueued emails
    -- that have no provider ID yet are not affected
    CONSTRAINT uq_email_events_provider_message_id
        UNIQUE (provider_message_id),

    CONSTRAINT ck_email_events_status_valid
        CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'BOUNCED')),

    -- recipient_email must be a meaningful value — a blank string is not an address
    CONSTRAINT ck_email_events_email_not_empty
        CHECK (length(trim(recipient_email)) > 0),

    -- mirror the lowercase invariant enforced across all email columns in this schema
    CONSTRAINT ck_email_events_email_lowercase
        CHECK (recipient_email = lower(recipient_email)),

    -- email_type must carry meaningful content — a blank type is undiagnosable
    CONSTRAINT ck_email_events_email_type_not_empty
        CHECK (length(trim(email_type)) > 0),

    -- SENT and BOUNCED both require a sent_at timestamp:
    --   SENT    → email was accepted by the provider — timestamp is known
    --   BOUNCED → email was sent and then rejected downstream — it did leave the system
    --   QUEUED / FAILED → email never left — sent_at must remain NULL
    CONSTRAINT ck_email_events_sent_at_consistency
        CHECK (
            status NOT IN ('SENT', 'BOUNCED')
            OR sent_at IS NOT NULL
        ),

    -- sent_at cannot be recorded before the row was created
    CONSTRAINT ck_email_events_sent_at_after_created
        CHECK (sent_at IS NULL OR sent_at >= created_at)
);
