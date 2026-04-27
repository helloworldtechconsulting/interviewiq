-- =============================================================================
-- V028__auth_add_user_last_login.sql
-- purpose: add last_login_at to users for security monitoring and
--          dormant-account detection.
--
-- USE CASES:
--   1. Security dashboard: "show accounts that haven't logged in for 90 days"
--   2. Anomaly detection: unexpected login after long dormancy → alert
--   3. Admin UI: "last seen" column on the team members page
--   4. Scheduled job: auto-deactivate accounts idle for > 180 days
--      (GDPR / data-minimisation hygiene)
--
-- NOTES:
--   - NULL on existing rows — correct; no historical login time is available
--   - AuthService.login() must SET last_login_at = now() on every successful
--     password or OAuth login
--   - Refresh token exchanges do NOT update last_login_at — that is an
--     implementation-level authentication event, not a user-initiated login
-- =============================================================================


ALTER TABLE users
    ADD COLUMN last_login_at TIMESTAMPTZ;


-- last_login_at cannot predate the account creation time;
-- guards against clock-skew bugs in the application layer
ALTER TABLE users
    ADD CONSTRAINT ck_users_last_login_at_after_created
        CHECK (last_login_at IS NULL OR last_login_at >= created_at);


-- ── INDEX: dormant-account detection ─────────────────────────────────────────
-- partial: NULL rows (never logged in) are excluded — they are handled
-- separately by a "never-activated accounts" cleanup query
CREATE INDEX idx_users_last_login_at
    ON users (last_login_at DESC)
    WHERE last_login_at IS NOT NULL;
