-- =============================================================================
-- V036__email_create_email_suppressions_table.sql
-- purpose: create the email_suppressions table for SES bounce and complaint
--          management to maintain sender reputation and comply with AWS SES
--          account-level suppression requirements.
--
-- CONTEXT:
--   AWS SES requires senders to honour bounce and complaint notifications.
--   Continued sending to bounced/complaining addresses:
--     (a) degrades sender reputation → SES account rate-limiting or suspension
--     (b) violates AWS Acceptable Use Policy for SES
--
--   Every outbound email must be checked against this table BEFORE dispatch.
--   If the recipient email is in email_suppressions, the send is silently skipped
--   and an email_events row is written with status = 'SUPPRESSED'.
--
-- SUPPRESSION REASONS:
--   BOUNCE    — address rejected by the receiving mail server (invalid mailbox,
--               domain does not exist, full inbox)
--   COMPLAINT — recipient marked the email as spam in their mail client;
--               SES delivers a complaint notification via SNS
--   MANUAL    — added by a platform admin (e.g. on user request or legal hold)
--
-- DEDUPLICATION:
--   The UNIQUE constraint on email ensures a single row per address regardless
--   of how many bounces or complaints arrive. Duplicate webhook events from
--   SES are handled by the application layer (check for existence before INSERT).
--
-- GDPR NOTE:
--   Suppressed email addresses are retained indefinitely by default — deleting
--   them allows the same address to be added to a future campaign, re-triggering
--   a bounce or complaint.  A "right to erasure" request should replace the email
--   value with a one-way hash rather than deleting the row.
-- =============================================================================


CREATE TABLE email_suppressions (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid(),

    -- lower-cased email address; enforced by CHECK constraint below
    email                    VARCHAR(255) NOT NULL,

    reason                   VARCHAR(50)  NOT NULL,

    -- Provider-assigned notification ID for traceability back to the SNS/SES event.
    -- Stored for audit purposes only; not used for deduplication (email column handles that).
    provider_notification_id VARCHAR(255),

    -- Optional note recorded when reason = MANUAL (e.g. "user requested opt-out via support ticket")
    notes                    TEXT,

    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- No updated_at — once an address is suppressed, only the reason might change.
    -- A reason change represents a new suppression event; prefer INSERT OR UPDATE
    -- (application-layer upsert) rather than a silent UPDATE path.

    CONSTRAINT pk_email_suppressions
        PRIMARY KEY (id),

    -- one row per email address — duplicates are rejected at INSERT time so the
    -- application can rely on a simple EXISTS check before any email dispatch
    CONSTRAINT uq_email_suppressions_email
        UNIQUE (email),

    CONSTRAINT ck_email_suppressions_reason_valid
        CHECK (reason IN ('BOUNCE', 'COMPLAINT', 'MANUAL')),

    -- mirror the lowercase invariant enforced across all email columns in this schema
    CONSTRAINT ck_email_suppressions_email_lowercase
        CHECK (email = lower(email)),

    -- must be a non-empty string — blank strings are not email addresses
    CONSTRAINT ck_email_suppressions_email_not_empty
        CHECK (length(trim(email)) > 0),

    -- notes must carry content if provided — a blank note is not useful
    CONSTRAINT ck_email_suppressions_notes_not_empty
        CHECK (notes IS NULL OR length(trim(notes)) > 0)
);


-- ── INDEX ─────────────────────────────────────────────────────────────────────
-- Pre-send suppression check: EXISTS (SELECT 1 FROM email_suppressions WHERE email = ?)
-- Called on every outbound email dispatch — must be as fast as possible.
-- The UNIQUE constraint on email already creates a B-tree index that serves this
-- query; no additional index is needed.
-- (Documented here explicitly so future developers do not add a redundant one.)
