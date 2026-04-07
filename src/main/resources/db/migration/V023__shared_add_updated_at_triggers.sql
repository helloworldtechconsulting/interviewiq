-- =============================================================================
-- V023__shared_add_updated_at_triggers.sql
-- purpose: (1) install the shared fn_set_updated_at() trigger function
--          (2) attach BEFORE UPDATE triggers to every mutable table
--
-- DESIGN NOTE (M6 resolution):
--   updated_at columns are now defined directly in the source table DDL:
--     interview_sessions → V007   evaluation_reports → V009   wallets → V010
--   This migration no longer issues ALTER TABLE ADD COLUMN for updated_at.
--   Each table file is fully self-describing; a developer reading V007 sees
--   every session column without needing to cross-reference V023.
--
-- TABLES RECEIVING TRIGGERS (all mutable tables carrying updated_at):
--   companies, users, job_openings, candidates,
--   interview_sessions, evaluation_reports, wallets
--
-- TABLES INTENTIONALLY EXCLUDED (append-only or write-once by design):
--   refresh_tokens      — no meaningful UPDATE path; tokens are created and revoked
--   otp_records         — marked used=TRUE on consumption; no other mutations
--   session_notes       — append-only; no edit flow in Phase 1
--   wallet_transactions — immutable ledger; rows are never updated after creation
--   webhook_events      — processed flag transition only; no updated_at column
--   storage_objects     — S3 metadata set at upload time; no subsequent updates
--   audit_logs          — write-once by design
--   email_events        — append-only delivery log
-- =============================================================================


-- =============================================================================
-- SHARED TRIGGER FUNCTION
-- =============================================================================

-- Single reusable function — not table-specific. PostgreSQL RETURNS TRIGGER
-- functions are polymorphic: NEW and OLD refer to the triggering row's column
-- set regardless of which table the trigger fires on. One function, seven triggers.
--
-- CREATE OR REPLACE: idempotent; safe to re-run in test environments.
CREATE OR REPLACE FUNCTION fn_set_updated_at()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Stamp the NEW row image (post-UPDATE) before it is written to disk.
    -- Returning NEW is required; returning NULL would silently cancel the UPDATE.
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;


-- =============================================================================
-- TRIGGERS — one per mutable table
-- =============================================================================
-- Naming convention (migration plan Section 2.3):
--   trg_{table}_before_update_set_updated_at
--
-- All triggers fire BEFORE UPDATE FOR EACH ROW.

CREATE TRIGGER trg_companies_before_update_set_updated_at
    BEFORE UPDATE ON companies
    FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();

CREATE TRIGGER trg_users_before_update_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();

CREATE TRIGGER trg_job_openings_before_update_set_updated_at
    BEFORE UPDATE ON job_openings
    FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();

CREATE TRIGGER trg_candidates_before_update_set_updated_at
    BEFORE UPDATE ON candidates
    FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();

CREATE TRIGGER trg_interview_sessions_before_update_set_updated_at
    BEFORE UPDATE ON interview_sessions
    FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();

CREATE TRIGGER trg_evaluation_reports_before_update_set_updated_at
    BEFORE UPDATE ON evaluation_reports
    FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();

CREATE TRIGGER trg_wallets_before_update_set_updated_at
    BEFORE UPDATE ON wallets
    FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();
