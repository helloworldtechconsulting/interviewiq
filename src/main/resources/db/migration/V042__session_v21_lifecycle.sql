-- =============================================================================
-- V042 — Session lifecycle for PRD v2.1 §7.4.4
--
-- Two new states and one rename:
--
--   SCHEDULED    NEW. The candidate has chosen a time. It exists because the
--                candidate now books for themselves, so there is a real interval
--                between invite and start that the recruiter should see. Skipped
--                entirely when the candidate picks "Start now".
--   IN_PROGRESS  Renamed from STARTED, to match the spec's vocabulary.
--   EVALUATING   NEW. Interview finished, scoring still running. This state is
--                user-visible on purpose: the candidate is told the interview is
--                complete, and the recruiter is shown that scoring is pending.
--                The PRD is explicit that it must not be hidden behind a spinner
--                on the report page — recruiters running hiring drives need to
--                know which reports are outstanding.
--
-- Also adds the columns candidate-driven scheduling and the readiness gate need:
--
--   scheduled_start_at   when the candidate booked for. Replaces scheduled_at,
--                        which was the employer-slot design's column.
--   duration_tier        copied from the job at session creation, so that
--                        changing a job's tier later cannot retroactively alter
--                        the hard timer or bucket occupancy of a booked session.
--   questions_ready_at   what the readiness gate (§7.4.3) actually tests, and
--                        the measurement point for the question-generation SLA.
--   resume_missing       recorded when a candidate has no résumé, so the report
--                        can note that questions were generated from the JD alone.
-- =============================================================================

-- ── Lifecycle states ────────────────────────────────────────────────────────
-- Drop first: the existing constraint would reject the UPDATE below.
ALTER TABLE interview_sessions
    DROP CONSTRAINT IF EXISTS ck_interview_sessions_status_valid;

UPDATE interview_sessions
SET status = 'IN_PROGRESS'
WHERE status = 'STARTED';

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_status_valid
        CHECK (status IN (
            'INVITED',
            'SCHEDULED',
            'IN_PROGRESS',
            'EVALUATING',
            'COMPLETED',
            'EXPIRED',
            'ERROR',
            'CANCELLED'
        ));

-- V033's partial index referenced the old state name.
DROP INDEX IF EXISTS idx_interview_sessions_company_scheduled_at;

-- ── Scheduling columns ──────────────────────────────────────────────────────
ALTER TABLE interview_sessions
    RENAME COLUMN scheduled_at TO scheduled_start_at;

ALTER TABLE interview_sessions
    DROP CONSTRAINT IF EXISTS ck_interview_sessions_scheduled_at_after_created;

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_scheduled_start_after_created
        CHECK (scheduled_start_at IS NULL OR scheduled_start_at > created_at);

ALTER TABLE interview_sessions
    ADD COLUMN duration_tier      VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN questions_ready_at TIMESTAMPTZ,
    ADD COLUMN resume_missing     BOOLEAN     NOT NULL DEFAULT FALSE;

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_duration_tier_valid
        CHECK (duration_tier IN ('QUICK', 'STANDARD', 'IN_DEPTH', 'COMPREHENSIVE'));

-- A session in SCHEDULED must actually carry the time it was scheduled for.
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_scheduled_requires_start_at
        CHECK (status <> 'SCHEDULED' OR scheduled_start_at IS NOT NULL);

-- Backfill: sessions already carrying questions had them ready by the time the
-- generation pipeline reported DONE. updated_at is the closest available proxy;
-- questions_ready_at is only read forward, for the readiness gate and the SLA
-- metric, so an approximate historical value is harmless.
UPDATE interview_sessions
SET questions_ready_at = updated_at
WHERE question_generation_status = 'DONE'
  AND questions_ready_at IS NULL;

-- Scheduling queries are "which sessions are booked and still ahead of us".
CREATE INDEX idx_interview_sessions_company_scheduled_start
    ON interview_sessions (company_id, scheduled_start_at ASC)
    WHERE status IN ('INVITED', 'SCHEDULED', 'IN_PROGRESS');

-- The readiness gate polls this per session while the candidate waits.
CREATE INDEX idx_interview_sessions_questions_ready
    ON interview_sessions (id)
    WHERE questions_ready_at IS NULL;
