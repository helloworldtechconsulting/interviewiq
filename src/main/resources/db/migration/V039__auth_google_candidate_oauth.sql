CREATE INDEX IF NOT EXISTS idx_users_google_subject
    ON users (google_subject)
    WHERE google_subject IS NOT NULL;

ALTER TABLE candidates
    ADD COLUMN IF NOT EXISTS google_subject  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS google_email    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS google_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_candidates_google_subject
    ON candidates (google_subject)
    WHERE google_subject IS NOT NULL;

COMMENT ON COLUMN candidates.google_subject  IS 'Google account subject (sub claim) — immutable per Google account';
COMMENT ON COLUMN candidates.google_email    IS 'Email address from the verified Google ID token';
COMMENT ON COLUMN candidates.google_verified IS 'True once CandidateAuthController has verified the Google ID token';
