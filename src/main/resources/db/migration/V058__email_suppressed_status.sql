-- =============================================================================
-- V058 — record sends that were withheld because the address is suppressed
-- =============================================================================
--
-- INTIQ-32. Once a hard bounce or a spam complaint puts an address on the
-- suppression list, later sends to it are not attempted. Those attempts still
-- need a row: "the invite never arrived" is a support question, and the answer
-- ("we stopped sending on 3 March because the address hard-bounced") only
-- exists if the withheld attempts were recorded.
--
-- SUPPRESSED is a distinct status rather than a reuse of FAILED. FAILED means
-- we tried and SMTP refused — transient and retryable. SUPPRESSED means we
-- deliberately did not try. Collapsing the two would make the deliverability
-- numbers lie in the direction that matters: a rising FAILED count reads as an
-- outage worth paging someone about, a rising SUPPRESSED count reads as a
-- list-hygiene problem. Those want different responses.
--
-- ck_email_events_sent_at_consistency is deliberately left alone. It reads
-- "status NOT IN ('SENT','BOUNCED') OR sent_at IS NOT NULL", so a new status
-- lands on the NULL-permitted side automatically — which is exactly right for
-- SUPPRESSED, an email that never left the system.
-- =============================================================================

ALTER TABLE email_events
    DROP CONSTRAINT IF EXISTS ck_email_events_status_valid;

ALTER TABLE email_events
    ADD CONSTRAINT ck_email_events_status_valid
        CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'BOUNCED', 'SUPPRESSED'));

-- Deliverability triage: "which addresses are we still bouncing on, and how
-- much mail are we withholding?" Partial, because these are a small minority of
-- rows and the healthy-path queries must not pay to index around them.
CREATE INDEX IF NOT EXISTS idx_email_events_undelivered
    ON email_events (recipient_email, created_at DESC)
    WHERE status IN ('BOUNCED', 'SUPPRESSED', 'FAILED');
