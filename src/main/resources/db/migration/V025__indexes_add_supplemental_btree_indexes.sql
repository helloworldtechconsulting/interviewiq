-- =============================================================================
-- V025__indexes_add_supplemental_btree_indexes.sql
-- purpose: B-tree indexes specified in the migration plan (Section 3.2,
--          Categories A–G) that were not included in V020.
--
-- V020 covered core session-lifecycle, billing, and webhook indexes.
-- This migration completes the full index specification across all categories.
--
-- NOTES ON EXISTING V020 INDEXES:
--   idx_interview_sessions_company_status (V020): ON (company_id, status)
--   idx_sessions_company_status_paginated below adds created_at DESC for
--   paginated dashboard queries; both indexes retained (planner picks per query).
--
--   idx_webhook_events_provider_event_type (V020): ON (provider, event_type)
--   idx_webhook_events_provider_type_paginated below adds created_at DESC;
--   both retained for same reason.
--
-- DEFERRED TO V026 (require columns added there):
--   idx_wallet_txn_razorpay_order — requires razorpay_order_id (added in V026)
--   idx_wallet_txn_pending        — requires status column (added in V026)
-- =============================================================================


-- -----------------------------------------------------------------------------
-- users
-- -----------------------------------------------------------------------------

CREATE INDEX idx_users_company_id
    ON users (company_id);

-- partial: google_subject IS NULL for all password-auth users
CREATE INDEX idx_users_google_subject
    ON users (google_subject)
    WHERE google_subject IS NOT NULL;


-- -----------------------------------------------------------------------------
-- refresh_tokens
-- -----------------------------------------------------------------------------

-- Logout: revoke all tokens for a user
--   UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = ?
CREATE INDEX idx_refresh_tokens_user_id
    ON refresh_tokens (user_id);


-- -----------------------------------------------------------------------------
-- otp_records
-- -----------------------------------------------------------------------------

-- OTP validation: fetch most recent OTP for email + purpose, newest first
--   WHERE email = ? AND purpose = ? ORDER BY created_at DESC LIMIT 1
CREATE INDEX idx_otp_records_email_purpose
    ON otp_records (email, purpose, created_at DESC);

-- Expired OTP cleanup job; partial: used = TRUE rows are already spent
--   WHERE used = FALSE AND expires_at < NOW()
CREATE INDEX idx_otp_records_cleanup
    ON otp_records (expires_at)
    WHERE used = FALSE;


-- -----------------------------------------------------------------------------
-- job_openings
-- -----------------------------------------------------------------------------

-- Session creation validation: confirm the target job opening is ACTIVE.
-- partial: only ACTIVE openings are relevant — ARCHIVED/CLOSED cannot receive sessions
CREATE INDEX idx_job_openings_active
    ON job_openings (company_id)
    WHERE status = 'ACTIVE';


-- -----------------------------------------------------------------------------
-- interview_sessions
-- -----------------------------------------------------------------------------

-- Session list on the job opening detail page, optionally filtered by status
CREATE INDEX idx_sessions_job_opening_id
    ON interview_sessions (job_opening_id, status);

-- Stale session recovery job; partial: only STARTED sessions are scanned
--   WHERE status = 'STARTED' AND started_at < NOW() - INTERVAL '2 hours'
CREATE INDEX idx_sessions_started_stale
    ON interview_sessions (started_at)
    WHERE status = 'STARTED';

-- Dashboard: paginated session list filtered by status, newest first.
-- Eliminates filesort on: WHERE company_id = ? AND status = ? ORDER BY created_at DESC
CREATE INDEX idx_sessions_company_status_paginated
    ON interview_sessions (company_id, status, created_at DESC);


-- -----------------------------------------------------------------------------
-- evaluation_reports (L4)
-- -----------------------------------------------------------------------------

-- Company-level aggregate queries: AVG score per company, score distributions.
-- Required by the dashboard "company performance" view.
--   WHERE company_id = ?
--   AVG(technical_score) GROUP BY company_id
CREATE INDEX idx_evaluation_reports_company_id
    ON evaluation_reports (company_id);


-- -----------------------------------------------------------------------------
-- audit_logs (L3)
-- -----------------------------------------------------------------------------

-- Security investigation: find all actions performed by a specific user.
--   WHERE user_id = ? ORDER BY created_at DESC
-- partial: user_id IS NULL for system-generated events with no actor —
-- excluded since those rows never match a user-specific security query.
CREATE INDEX idx_audit_logs_user_id
    ON audit_logs (user_id)
    WHERE user_id IS NOT NULL;


-- -----------------------------------------------------------------------------
-- storage_objects (M3)
-- -----------------------------------------------------------------------------

-- Entity-scoped file lookup: find the resume for candidate X, the JD for job Y.
--   WHERE entity_id = ? AND object_type = ?
-- partial: entity_id IS NULL for system-level or company-wide files
-- (e.g. bulk evaluation exports) — excluded to keep the index focused.
CREATE INDEX idx_storage_objects_entity_id
    ON storage_objects (entity_id)
    WHERE entity_id IS NOT NULL;


-- -----------------------------------------------------------------------------
-- email_events
-- -----------------------------------------------------------------------------

-- Company email history: paginated delivery log, newest first
--   WHERE company_id = ? ORDER BY created_at DESC
CREATE INDEX idx_email_events_company_id
    ON email_events (company_id, created_at DESC);


-- -----------------------------------------------------------------------------
-- webhook_events
-- -----------------------------------------------------------------------------

-- Debug/investigation: paginated event list by provider and type, newest first.
-- Eliminates filesort on: WHERE provider = ? AND event_type = ? ORDER BY created_at DESC
CREATE INDEX idx_webhook_events_provider_type_paginated
    ON webhook_events (provider, event_type, created_at DESC);
