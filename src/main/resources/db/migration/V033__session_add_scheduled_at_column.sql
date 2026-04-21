-- =============================================================================
-- V033__session_add_scheduled_at_column.sql
-- purpose: add scheduled_at to interview_sessions.
--
-- ISSUE: CreateSessionRequest.java declares `scheduledAt` and the frontend
-- sends it on every session creation call, but V007 never added the column.
-- The value is silently discarded by Hibernate on every INSERT.
--
-- COLUMN SEMANTICS:
--   scheduled_at  — the target interview time set by the recruiter at session
--                   creation time. Shown to the candidate in the invite email
--                   and on the CandidateRoomPage ("Your interview is scheduled
--                   for April 20 at 3:00 PM IST").
--
--   NOT the same as started_at (when the candidate actually clicked Start).
--   NOT the same as invite_expires_at (the deadline before the link expires).
--
--   Timeline:
--     created_at      ← session row created (invite issued)
--     scheduled_at    ← target interview time (recruiter's intent)
--     started_at      ← candidate joined (actual)
--     ended_at        ← interview finished
--     invite_expires_at ← invite link goes stale (48h after created_at)
--
-- NULLABLE: NULL for sessions created before this migration. New sessions
-- from the application layer SHOULD always supply scheduled_at; the
-- CreateSessionRequest validation enforces this at the API boundary.
--
-- WHY NO availability_slots TABLE:
--   InterviewIQ uses Recall.ai bot-per-session — there is no shared human
--   interviewer resource to protect from double-booking. Multiple sessions
--   can and do run in parallel at the same scheduled time with no conflict.
--   A slot-table (Calendly-style) solves a problem this product does not have.
--   scheduled_at on the session row is sufficient.
-- =============================================================================


ALTER TABLE interview_sessions
    ADD COLUMN scheduled_at TIMESTAMPTZ;


-- scheduled_at must be strictly after the session was created;
-- a session cannot be scheduled in the past relative to when the invite was issued
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_scheduled_at_after_created
        CHECK (scheduled_at IS NULL OR scheduled_at > created_at);


-- ── INDEX: dashboard sorting by upcoming sessions ─────────────────────────────
-- Employer dashboard: "upcoming interviews" list ordered by soonest first.
--   WHERE company_id = ? AND status = 'INVITED' ORDER BY scheduled_at ASC
-- partial: COMPLETED/EXPIRED/CANCELLED sessions are excluded from this view
CREATE INDEX idx_interview_sessions_company_scheduled_at
    ON interview_sessions (company_id, scheduled_at ASC)
    WHERE status IN ('INVITED', 'STARTED');
