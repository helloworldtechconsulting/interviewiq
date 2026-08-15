-- =============================================================================
-- V055 — when a recruiter first opened an evaluation report
--
-- Supports the "reports awaiting review" counter on the dashboard (INTIQ-73).
--
-- WHY THIS IS WORTH A COLUMN
--
-- The dashboard's other counters describe work the system is doing. This one
-- describes work the *recruiter* has not done, which is the only number on the
-- page that prompts an action rather than reporting a state. A hiring drive
-- that produces twelve reports over a weekend is a pile of unread assessments on
-- Monday morning, and "9 reports you have not read" is the line that gets them
-- read.
--
-- FIRST OPEN, NOT LAST
--
-- Stamped once and never updated. "Last viewed" would answer a different and
-- less useful question — a recruiter re-reading a report they have already
-- actioned should not make it look fresh again.
--
-- Nullable with no backfill: every report that exists today predates the
-- concept, and guessing that historical reports were read would inflate the
-- "done" side of a counter whose whole purpose is to be honest about the
-- backlog. They count as unread, which is at worst pessimistic.
-- =============================================================================

ALTER TABLE evaluation_reports
    ADD COLUMN IF NOT EXISTS viewed_at TIMESTAMPTZ;

COMMENT ON COLUMN evaluation_reports.viewed_at IS
    'When an employer first opened this report. Set once, never updated — re-reading an actioned report should not make it look unread.';

-- The counter asks "completed reports for this company with no viewed_at".
-- Indexing only the unviewed rows keeps the index proportional to the backlog
-- rather than to the whole history, which is the opposite shape: the backlog
-- should stay small while the history grows without bound.
CREATE INDEX IF NOT EXISTS ix_evaluation_reports_unviewed
    ON evaluation_reports (company_id)
    WHERE viewed_at IS NULL;
