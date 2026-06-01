-- =============================================================================
-- V039__auth_google_candidate_oauth.sql
--
-- Adds Google OAuth identity fields to:
--   1. users       — employer accounts (google_subject index already implied
--                    by UserRepository.findByGoogleSubject)
--   2. candidates  — add google_subject, google_email, google_verified so a
--                    candidate can link their Google identity during an interview
--
-- All new columns are nullable — existing rows are unaffected.
-- google_verified defaults to FALSE; set TRUE when GoogleOAuthService
-- successfully verifies the ID token.
-- =============================================================================

-- ── 1. users: index on google_subject for O(1) lookup ────────────────────────
-- The column itself was added in an earlier migration; we only add the index here.
CREATE INDEX IF NOT EXISTS idx_users_google_subject
    ON users (google_subject)
    WHERE google_subject IS NOT NULL;

-- ── 2. candidates: Google identity fields ────────────────────────────────────
ALTER TABLE candidates
    ADD COLUMN IF NOT EXISTS google_subject  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS google_email    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS google_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Index to support CandidateRepository.findByGoogleSubject
CREATE INDEX IF NOT EXISTS idx_candidates_google_subject
    ON candidates (google_subject)
    WHERE google_subject IS NOT NULL;

COMMENT ON COLUMN candidates.google_subject  IS 'Google account subject (sub claim) — immutable per Google account';
COMMENT ON COLUMN candidates.google_email    IS 'Email address from the verified Google ID token';
COMMENT ON COLUMN candidates.google_verified IS 'True once CandidateAuthController has verified the Google ID token';
