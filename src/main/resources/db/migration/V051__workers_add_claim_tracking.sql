-- =============================================================================
-- V051 — Claim tracking for the polling workers
--
-- PRD v2.1 §7.9. "Every scheduled worker in the codebase currently polls with a
-- plain derived query and no locking of any kind. On six pods, all six process
-- the same rows: six times the LLM bill, racing writes on the same report row,
-- generationAttempts races that fail healthy sessions, and duplicate wallet
-- settlement. This is a hard blocker on autoscaling."
--
-- The claim itself is a SELECT ... FOR UPDATE SKIP LOCKED with an explicit LIMIT,
-- issued in the repositories. This migration adds the one piece of state that
-- pattern needs but cannot derive: WHEN a row was claimed.
--
-- WHY A DEDICATED COLUMN rather than reusing updated_at. Staleness recovery has
-- to answer "was this row claimed by a pod that then died?" — and updated_at
-- moves for every unrelated write, so a row touched by some other operation
-- would look freshly claimed and never be recovered. claimed_at is written only
-- by the claim statement, which makes the recovery predicate exact.
--
-- A row is reclaimable when it is IN_PROGRESS and its claimed_at is older than
-- the staleness threshold. That is what replaces the current design's habit of
-- polling IN_PROGRESS rows unconditionally for "crash recovery" — which on more
-- than one pod means every pod reprocessing work another pod is actively doing.
-- =============================================================================

ALTER TABLE evaluation_reports
    ADD COLUMN claimed_at TIMESTAMPTZ;

ALTER TABLE interview_sessions
    ADD COLUMN questions_claimed_at TIMESTAMPTZ;

ALTER TABLE job_openings
    ADD COLUMN jd_extraction_claimed_at TIMESTAMPTZ;

ALTER TABLE candidates
    ADD COLUMN resume_extraction_claimed_at TIMESTAMPTZ;

-- Claim queries scan for PENDING work, plus IN_PROGRESS work whose claim has
-- gone stale. These partial indexes keep that scan off the full table.

CREATE INDEX idx_evaluation_reports_claimable
    ON evaluation_reports (created_at)
    WHERE generation_status IN ('PENDING', 'IN_PROGRESS');

CREATE INDEX idx_interview_sessions_questions_claimable
    ON interview_sessions (created_at)
    WHERE question_generation_status IN ('PENDING', 'IN_PROGRESS');

CREATE INDEX idx_job_openings_jd_claimable
    ON job_openings (created_at)
    WHERE jd_extraction_status IN ('PENDING', 'IN_PROGRESS');

CREATE INDEX idx_candidates_resume_claimable
    ON candidates (created_at)
    WHERE resume_extraction_status IN ('PENDING', 'IN_PROGRESS');
