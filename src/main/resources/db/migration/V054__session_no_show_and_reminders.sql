-- =============================================================================
-- V054 — NO_SHOW state, reminder idempotency stamps, low-balance notification
--
-- Closes the half of INTIQ-92 that was never built, plus INTIQ-71.
--
-- THE GAP THIS FILLS
--
-- A candidate books a slot, ₹100 is reserved, and they do not turn up. Until
-- now nothing noticed. The session sat in SCHEDULED holding both the money and
-- its capacity buckets until SessionExpiryJob eventually swept it — but that
-- job keys off invite_expires_at, which is a completely different clock. A
-- candidate who books for tomorrow and no-shows keeps the reservation until
-- their *invite* expires, which could be days later.
--
-- NO_SHOW gives the sweep somewhere to move the session to, which is what lets
-- the reservation and the buckets be released at the right moment.
--
-- WHY THE REMINDER STAMPS ARE COLUMNS AND NOT A LOG TABLE
--
-- The sweeps run on every worker pod. FOR UPDATE SKIP LOCKED stops two pods
-- claiming the same row in the same pass, but it does not by itself make a
-- resend impossible across passes — a pod that crashes after sending and before
-- committing would resend on recovery. A timestamp on the row, written in the
-- same transaction as the claim, makes the send idempotent at the row level:
-- the query cannot select a session whose stamp is already set.
--
-- This matters more than the usual idempotency argument because the failure is
-- visible to the candidate. Six pods means six copies of "your interview is in
-- one hour" in someone's inbox, and a sender reputation hit that outlasts the
-- bug.
-- =============================================================================

-- ── 1. NO_SHOW joins the status CHECK ────────────────────────────────────────

ALTER TABLE interview_sessions
    DROP CONSTRAINT IF EXISTS ck_interview_sessions_status_valid;

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_status_valid
        CHECK (status IN (
            'INVITED',
            'SCHEDULED',
            'IN_PROGRESS',
            'EVALUATING',
            'COMPLETED',
            'EXPIRED',
            'CANCELLED',
            'NO_SHOW',
            'ERROR'
        ));

-- ── 2. Reminder idempotency stamps ───────────────────────────────────────────

ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS reminder_24h_sent_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reminder_1h_sent_at  TIMESTAMPTZ;

COMMENT ON COLUMN interview_sessions.reminder_24h_sent_at IS
    'Set when the T-24h reminder is sent. Null means unsent; the sweep selects on IS NULL, so a set value makes a resend unreachable.';

COMMENT ON COLUMN interview_sessions.reminder_1h_sent_at IS
    'Set when the T-1h reminder is sent. Same idempotency contract as reminder_24h_sent_at.';

-- Partial indexes: the sweeps ask "which SCHEDULED sessions are due a reminder
-- I have not sent yet", which is a small slice of a table that is mostly
-- terminal rows. Indexing only the unsent ones keeps these indexes proportional
-- to the pending work rather than to the history.
CREATE INDEX IF NOT EXISTS ix_sessions_reminder_24h_due
    ON interview_sessions (scheduled_start_at)
    WHERE status = 'SCHEDULED' AND reminder_24h_sent_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_sessions_reminder_1h_due
    ON interview_sessions (scheduled_start_at)
    WHERE status = 'SCHEDULED' AND reminder_1h_sent_at IS NULL;

-- The no-show sweep's own lookup: scheduled sessions whose start time plus the
-- grace period has passed.
CREATE INDEX IF NOT EXISTS ix_sessions_no_show_due
    ON interview_sessions (scheduled_start_at)
    WHERE status = 'SCHEDULED';

-- ── 3. Low-balance notification stamp (INTIQ-71) ─────────────────────────────

ALTER TABLE wallets
    ADD COLUMN IF NOT EXISTS low_balance_notified_at TIMESTAMPTZ;

COMMENT ON COLUMN wallets.low_balance_notified_at IS
    'When the low-balance email was last sent. Cleared on top-up, so a company that tops up and later drops back below the threshold is warned again — but a company that simply stays low is not emailed on every settle.';
